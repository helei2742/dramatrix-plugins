#!/usr/bin/env python3
"""Build a signed plugin catalog and publish immutable plugin packages."""

from __future__ import annotations

import argparse
import base64
import contextlib
import datetime as dt
import hashlib
import http.client
import json
import os
import pathlib
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.parse
from typing import Any

import plugin_package


class ReleaseError(Exception):
    pass


def fail(message: str) -> None:
    raise ReleaseError(message)


def canonical(value: Any) -> bytes:
    return plugin_package.jcs_bytes(value)


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_stream(stream: Any, out: pathlib.Path | None = None) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    target = out.open("wb") if out else None
    try:
        while True:
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            size += len(chunk)
            digest.update(chunk)
            if target:
                target.write(chunk)
    finally:
        if target:
            target.close()
    return size, digest.hexdigest()


def utc_now() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0)


def timestamp(value: dt.datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def semver_sort_key(value: str) -> tuple[Any, ...]:
    match = plugin_package.SEMVER.fullmatch(value)
    if not match:
        fail(f"invalid SemVer in plugin release plan: {value}")
    prerelease = match.group(4)
    if prerelease is None:
        prerelease_key: tuple[Any, ...] = (1,)
    else:
        identifiers = []
        for identifier in prerelease.split("."):
            identifiers.append((0, int(identifier)) if identifier.isdigit() else (1, identifier))
        prerelease_key = (0, *identifiers)
    return int(match.group(1)), int(match.group(2)), int(match.group(3)), prerelease_key, match.group(5) or ""


def validate_catalog_payload(payload: dict[str, Any]) -> None:
    def exact(value: dict[str, Any], required: set[str], optional: set[str], label: str) -> None:
        if not required.issubset(value) or set(value) - required - optional:
            fail(f"catalog {label} has missing or unknown fields")

    exact(payload, {"schemaVersion", "publisher", "channel", "generation", "generatedAt", "expiresAt", "plugins"}, set(), "payload")
    if payload["schemaVersion"] != 1 or payload["publisher"] != "dramatrix":
        fail("catalog schemaVersion or publisher is unsupported")
    if payload["channel"] not in ("stable", "beta"):
        fail("catalog channel is invalid")
    if not isinstance(payload["generation"], int) or isinstance(payload["generation"], bool) or payload["generation"] < 1:
        fail("catalog generation must be a positive integer")
    try:
        generated = dt.datetime.fromisoformat(payload["generatedAt"].replace("Z", "+00:00"))
        expires = dt.datetime.fromisoformat(payload["expiresAt"].replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError):
        fail("catalog timestamps must be RFC 3339 date-times")
    if generated.utcoffset() != dt.timedelta(0) or expires.utcoffset() != dt.timedelta(0):
        fail("catalog timestamps must be UTC")
    if generated > utc_now() + dt.timedelta(minutes=5):
        fail("catalog generatedAt is too far in the future")
    if expires <= generated or expires - generated > dt.timedelta(days=7):
        fail("catalog expiry must be after generation and no more than seven days later")
    if not isinstance(payload["plugins"], list):
        fail("catalog plugins must be an array")
    seen: set[str] = set()
    for plugin in payload["plugins"]:
        if not isinstance(plugin, dict):
            fail("catalog plugin entries must be objects")
        plugin_optional = {"runtime"} if plugin.get("type") == "media" else set()
        exact(plugin, {"id", "publisher", "type", "displayName", "versions"}, plugin_optional, "plugin entry")
        plugin_id = plugin.get("id")
        if not isinstance(plugin_id, str) or not plugin_package.PLUGIN_ID.fullmatch(plugin_id) or plugin_id in seen:
            fail("catalog plugin IDs must be unique official IDs")
        seen.add(plugin_id)
        plugin_type = plugin.get("type")
        if plugin.get("publisher") != "dramatrix" or plugin_type not in ("management", "media", "profile"):
            fail(f"catalog plugin identity is invalid: {plugin_id}")
        if not isinstance(plugin.get("displayName"), str) or not plugin["displayName"]:
            fail(f"catalog displayName is required: {plugin_id}")
        if plugin_type == "media" and plugin.get("runtime") not in ("api", "comfyui"):
            fail(f"catalog media runtime is invalid: {plugin_id}")
        if not isinstance(plugin.get("versions"), list) or not plugin["versions"]:
            fail(f"catalog plugin must include versions: {plugin_id}")
        versions: set[str] = set()
        for version in plugin["versions"]:
            if not isinstance(version, dict):
                fail("catalog version entries must be objects")
            exact(version, {"version", "status", "publishedAt", "compatibility", "innerManifest", "artifact"}, set(), "version")
            version_id = version.get("version")
            if not isinstance(version_id, str) or not plugin_package.SEMVER.fullmatch(version_id) or version_id in versions:
                fail(f"catalog versions must be unique SemVer values: {plugin_id}")
            versions.add(version_id)
            if version.get("status") not in ("available", "withdrawn"):
                fail(f"catalog version status is invalid: {plugin_id} {version_id}")
            try:
                published_at = dt.datetime.fromisoformat(version["publishedAt"].replace("Z", "+00:00"))
            except (AttributeError, TypeError, ValueError):
                fail(f"catalog publishedAt is invalid: {plugin_id} {version_id}")
            if published_at.utcoffset() != dt.timedelta(0):
                fail(f"catalog publishedAt must be UTC: {plugin_id} {version_id}")
            compatibility = version.get("compatibility")
            if not isinstance(compatibility, dict):
                fail("catalog compatibility must be an object")
            exact(compatibility, {"backendVersionRange", "pluginApi"}, {"managementUiApiRange"}, "compatibility")
            api = compatibility.get("pluginApi")
            if not isinstance(api, dict):
                fail("catalog pluginApi compatibility must be an object")
            exact(api, {"namespace", "range"}, set(), "pluginApi")
            expected_namespace = {"management": "management-api", "media": "media-plugin-api",
                                  "profile": "profile-manifest-schema"}[plugin_type]
            if api.get("namespace") != expected_namespace or not api.get("range") or not compatibility.get("backendVersionRange"):
                fail(f"catalog compatibility namespace or range is invalid: {plugin_id}")
            expected_manifest = {"management": "management-plugin.json", "media": "adapter.json", "profile": "plugin.json"}[plugin_type]
            if version.get("innerManifest") != expected_manifest:
                fail(f"catalog innerManifest does not match plugin type: {plugin_id}")
            artifact = version.get("artifact")
            if not isinstance(artifact, dict):
                fail("catalog artifact must be an object")
            exact(artifact, {"fileName", "size", "sha256", "signature", "sources"}, set(), "artifact")
            if (not isinstance(artifact.get("fileName"), str)
                    or pathlib.PurePosixPath(artifact["fileName"]).name != artifact["fileName"]
                    or "\\" in artifact["fileName"] or "\x00" in artifact["fileName"]):
                fail("catalog artifact fileName must be a basename")
            if not isinstance(artifact.get("size"), int) or isinstance(artifact["size"], bool) or artifact["size"] < 1:
                fail("catalog artifact size must be a positive integer")
            if not isinstance(artifact.get("sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", artifact["sha256"]):
                fail("catalog artifact SHA256 is invalid")
            signature = artifact.get("signature")
            if not isinstance(signature, dict):
                fail("catalog artifact signature must be an object")
            exact(signature, {"algorithm", "keyId", "value"}, set(), "artifact signature")
            if signature.get("algorithm") != "Ed25519" or not isinstance(signature.get("keyId"), str) or not signature["keyId"]:
                fail("catalog artifact signature metadata is invalid")
            try:
                sig = base64.urlsafe_b64decode(signature["value"] + "=" * (-len(signature["value"]) % 4))
            except Exception:
                fail("catalog artifact signature encoding is invalid")
            if len(sig) != 64:
                fail("catalog Ed25519 signature must contain 64 bytes")
            sources = artifact.get("sources")
            if not isinstance(sources, list) or not sources:
                fail("catalog artifact requires at least one download source")
            source_ids: set[str] = set()
            for source in sources:
                if not isinstance(source, dict):
                    fail("catalog source candidates must be objects")
                exact(source, {"sourceId", "url"}, set(), "source")
                if not isinstance(source.get("sourceId"), str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,62}", source["sourceId"]):
                    fail("catalog sourceId is invalid")
                if source["sourceId"] in source_ids:
                    fail("catalog sourceIds must be unique per artifact")
                source_ids.add(source["sourceId"])
                parsed = urllib.parse.urlsplit(str(source.get("url", "")))
                if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
                    fail("catalog source URL must be an HTTPS URL without credentials or fragment")


def load_json(path: pathlib.Path) -> dict[str, Any]:
    try:
        parsed = plugin_package.strict_json_loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"invalid JSON in {path}: {exc}")
    if not isinstance(parsed, dict):
        fail(f"expected a JSON object in {path}")
    return parsed


def openssl_sign(message: bytes, key: pathlib.Path) -> bytes:
    with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-catalog-") as directory:
        source = pathlib.Path(directory) / "payload.bin"
        target = pathlib.Path(directory) / "signature.bin"
        source.write_bytes(message)
        try:
            subprocess.run(
                ["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(key),
                 "-in", str(source), "-out", str(target)],
                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            )
        except FileNotFoundError:
            fail("OpenSSL is required to sign the plugin catalog")
        except subprocess.CalledProcessError as exc:
            fail(f"OpenSSL failed to sign the catalog: {exc.stderr.decode(errors='replace').strip()}")
        return target.read_bytes()


@contextlib.contextmanager
def signing_key_file(explicit_path: str | None):
    if explicit_path:
        path = pathlib.Path(explicit_path).expanduser().resolve()
        if not path.is_file():
            fail(f"plugin signing private key file not found: {path}")
        yield path
        return
    pem = os.environ.get("DRAMATRIX_PLUGIN_SIGNING_KEY_PEM")
    if not pem:
        fail("set DRAMATRIX_PLUGIN_SIGNING_KEY_PEM or pass --private-key")
    with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-release-signing-") as directory:
        path = pathlib.Path(directory) / "ed25519.pem"
        path.write_text(pem, encoding="utf-8")
        path.chmod(0o600)
        yield path


def verify_catalog(path: pathlib.Path, public_key: pathlib.Path) -> dict[str, Any]:
    envelope = load_json(path)
    if set(envelope) != {"payload", "signature"}:
        fail("catalog envelope has unknown or missing fields")
    payload = envelope.get("payload")
    signature = envelope.get("signature")
    if not isinstance(payload, dict) or not isinstance(signature, dict):
        fail("catalog must contain payload and signature objects")
    if signature.get("algorithm") != "Ed25519" or not signature.get("keyId"):
        fail("catalog signature metadata is unsupported or incomplete")
    try:
        sig_bytes = base64.urlsafe_b64decode(
            str(signature.get("value", "")) + "=" * (-len(str(signature.get("value", ""))) % 4)
        )
    except Exception as exc:
        fail(f"invalid catalog signature encoding: {exc}")
    with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-catalog-verify-") as directory:
        source = pathlib.Path(directory) / "payload.bin"
        sig = pathlib.Path(directory) / "signature.bin"
        source.write_bytes(b"Dramatrix-Plugin-Catalog-V1\n" + canonical(payload))
        sig.write_bytes(sig_bytes)
        result = subprocess.run(
            ["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(public_key),
             "-rawin", "-in", str(source), "-sigfile", str(sig)],
            check=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
    if result.returncode:
        fail("Ed25519 catalog signature verification failed")
    validate_catalog_payload(payload)
    return payload


def validate_destination(item: Any, allow_local_http: bool) -> dict[str, Any]:
    if not isinstance(item, dict):
        fail("each destination must be a JSON object")
    destination_id = item.get("id")
    if not isinstance(destination_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,62}", destination_id):
        fail(f"invalid destination id: {destination_id!r}")
    provider = item.get("provider")
    if provider not in ("github-release", "https-static"):
        fail(f"unsupported plugin release provider: {provider!r}")
    if not isinstance(item.get("required"), bool):
        fail(f"destination {destination_id} must declare required as a boolean")
    result = {"id": destination_id, "provider": provider, "required": item["required"]}
    source_id = item.get("downloadSourceId")
    if not isinstance(source_id, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,62}", source_id):
        fail(f"destination {destination_id} requires a distinct downloadSourceId")
    result["downloadSourceId"] = source_id
    if provider == "github-release":
        repository = item.get("repository")
        if not isinstance(repository, str) or not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            fail(f"destination {destination_id} requires repository owner/name")
        result["repository"] = repository
    else:
        base = item.get("baseUrl")
        parsed = urllib.parse.urlsplit(str(base or ""))
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            if not (allow_local_http and parsed.scheme == "http" and parsed.hostname in ("127.0.0.1", "::1", "localhost")
                    and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment):
                fail(f"destination {destination_id} baseUrl must be an HTTPS origin/path without credentials, query, or fragment")
        if parsed.port not in (None, 443) and parsed.scheme == "https":
            fail(f"destination {destination_id} must use HTTPS port 443")
        result["baseUrl"] = base.rstrip("/")
        token_env = item.get("tokenEnv")
        if token_env is not None:
            if not isinstance(token_env, str) or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", token_env):
                fail(f"destination {destination_id} tokenEnv is invalid")
            result["tokenEnv"] = token_env
        ca_bundle = item.get("caBundle")
        if ca_bundle is not None:
            if not allow_local_http or parsed.hostname not in ("127.0.0.1", "::1", "localhost"):
                fail("caBundle is allowed only for a loopback integration target")
            if not pathlib.Path(ca_bundle).is_file():
                fail(f"destination {destination_id} caBundle was not found")
            result["caBundle"] = str(pathlib.Path(ca_bundle).resolve())
    return result


def validate_initial_release_destinations(destinations: list[dict[str, Any]]) -> None:
    if (len(destinations) != 1 or not destinations[0].get("required")
            or destinations[0].get("provider") != "github-release"):
        fail("plugin release v1 requires exactly one required GitHub Releases destination")


def artifact_url(destination: dict[str, Any], plugin: dict[str, Any], filename: str) -> str:
    slug = plugin["id"].split("/", 1)[1]
    tag = f"plugin-{slug}-v{plugin['version']}"
    if destination["provider"] == "github-release":
        return f"https://github.com/{destination['repository']}/releases/download/{tag}/{urllib.parse.quote(filename)}"
    return f"{destination['baseUrl']}/plugins/dramatrix/{urllib.parse.quote(slug)}/{urllib.parse.quote(plugin['version'])}/{urllib.parse.quote(filename)}"


def catalog_paths(destination: dict[str, Any], channel: str, generation: int) -> tuple[str, str]:
    if destination["provider"] == "github-release":
        repo = destination["repository"]
        immutable_tag = f"plugin-catalog-{channel}-g{generation}"
        pointer_tag = f"plugin-catalog-{channel}"
        filename = f"catalog-{channel}-{generation}.json"
        return (f"https://github.com/{repo}/releases/download/{immutable_tag}/{filename}",
                f"https://github.com/{repo}/releases/download/{pointer_tag}/catalog-{channel}.json")
    base = destination["baseUrl"]
    return (f"{base}/catalogs/{channel}/{generation}.json",
            f"{base}/catalogs/{channel}/current.json")


def read_package_plugins(packages_dir: pathlib.Path, public_key: pathlib.Path,
                         destinations: list[dict[str, Any]], key_id: str,
                         previous_versions: dict[tuple[str, str], dict[str, str]]) -> list[dict[str, Any]]:
    archives = sorted(packages_dir.glob("*.zip"))
    if not archives:
        fail(f"no plugin ZIP archives found in {packages_dir}")
    plugins: list[dict[str, Any]] = []
    seen_versions: set[tuple[str, str]] = set()
    for archive in archives:
        signature_path = archive.with_suffix(archive.suffix + ".sig.json")
        package = plugin_package.verify_signature(archive, public_key)
        signature_envelope = load_json(signature_path)
        signature = signature_envelope["signature"]
        if signature.get("keyId") != key_id:
            fail(f"artifact signature key does not match release key ID: {archive.name}")
        entries = plugin_package.archive_entries(archive)
        inner = plugin_package.read_json_bytes(
            entries[package["innerManifest"]], package["innerManifest"]
        )
        identity = (package["id"], package["version"])
        if identity in seen_versions:
            fail(f"duplicate plugin ID/version in package directory: {package['id']} {package['version']}")
        seen_versions.add(identity)
        slug = package["id"].split("/", 1)[1]
        display_name = str(inner.get("displayName") or inner.get("title") or inner.get("label") or slug)
        artifact = {
            "fileName": archive.name,
            "path": str(archive.resolve()),
            "size": archive.stat().st_size,
            "sha256": sha256_file(archive),
            "signatureFileName": signature_path.name,
            "signaturePath": str(signature_path.resolve()),
            "signatureSize": signature_path.stat().st_size,
            "signatureSha256": sha256_file(signature_path),
            "signature": signature,
        }
        plugin = {
            "id": package["id"], "publisher": package["publisher"],
            "type": package["type"], "version": package["version"],
            "runtime": package.get("runtime"), "displayName": display_name,
            "innerManifest": package["innerManifest"],
            "compatibility": package["compatibility"],
            "artifact": artifact,
            "publishedAt": previous_versions.get(identity, {}).get("publishedAt"),
            "status": previous_versions.get(identity, {}).get("status", "available"),
        }
        plugin["artifact"]["sourceUrls"] = {
            destination["id"]: artifact_url(destination, plugin, archive.name)
            for destination in destinations
        }
        plugin["artifact"]["signatureSourceUrls"] = {
            destination["id"]: artifact_url(destination, plugin, signature_path.name)
            for destination in destinations
        }
        plugins.append(plugin)
    return plugins


def previous_catalog_versions(path: pathlib.Path, public_key: pathlib.Path, channel: str) -> dict[tuple[str, str], dict[str, str]]:
    payload = verify_catalog(path, public_key)
    if payload.get("channel") != channel:
        fail("previous catalog channel does not match the new release plan")
    result = {}
    for plugin in payload["plugins"]:
        for version in plugin["versions"]:
            result[(plugin["id"], version["version"])] = {
                "publishedAt": version["publishedAt"], "status": version["status"],
                "sha256": version["artifact"]["sha256"],
            }
    return result


def build_catalog_payload(plugins: list[dict[str, Any]], destinations: list[dict[str, Any]],
                          channel: str, generation: int, verified: set[str], now: dt.datetime) -> dict[str, Any]:
    expiry = now + dt.timedelta(days=7)
    catalog_plugins = []
    grouped: dict[str, list[dict[str, Any]]] = {}
    for plugin in plugins:
        grouped.setdefault(plugin["id"], []).append(plugin)
    for plugin_id, versions in sorted(grouped.items()):
        head = max(versions, key=lambda item: semver_sort_key(item["version"]))
        if any((item["type"], item.get("runtime"), item["publisher"]) !=
               (head["type"], head.get("runtime"), head["publisher"]) for item in versions):
            fail(f"plugin type, runtime, and publisher must stay stable across versions: {plugin_id}")
        catalog_versions = []
        for plugin in sorted(versions, key=lambda item: semver_sort_key(item["version"]), reverse=True):
            sources = [
                {"sourceId": dest["downloadSourceId"], "url": plugin["artifact"]["sourceUrls"][dest["id"]]}
                for dest in destinations if dest["id"] in verified
            ]
            if not sources:
                fail(f"plugin {plugin['id']} has no verified download source")
            artifact = plugin["artifact"]
            catalog_versions.append({
                "version": plugin["version"], "status": plugin.get("status", "available"),
                "publishedAt": plugin.get("publishedAt") or timestamp(now),
                "compatibility": plugin["compatibility"], "innerManifest": plugin["innerManifest"],
                "artifact": {
                    "fileName": artifact["fileName"], "size": artifact["size"],
                    "sha256": artifact["sha256"], "signature": artifact["signature"],
                    "sources": sources,
                },
            })
        item = {"id": plugin_id, "publisher": head["publisher"], "type": head["type"],
                "displayName": head["displayName"], "versions": catalog_versions}
        if head.get("runtime"):
            item["runtime"] = head["runtime"]
        catalog_plugins.append(item)
    return {
        "schemaVersion": 1, "publisher": "dramatrix", "channel": channel,
        "generation": generation, "generatedAt": timestamp(now),
        "expiresAt": timestamp(expiry), "plugins": catalog_plugins,
    }


def validate_plan(plan_path: pathlib.Path, require_catalog: bool = False) -> dict[str, Any]:
    plan = load_json(plan_path)
    if plan.get("schemaVersion") != 1 or plan.get("kind") != "plugin-release-plan":
        fail("unsupported plugin release plan schema")
    public_key = pathlib.Path(plan.get("catalogPublicKeyPath", ""))
    if not public_key.is_file():
        fail(f"catalog public key file not found: {public_key}")
    if plan.get("channel") not in ("stable", "beta"):
        fail("plugin release channel must be stable or beta")
    generation = plan.get("catalogGeneration")
    if not isinstance(generation, int) or isinstance(generation, bool) or generation < 1:
        fail("catalogGeneration must be a positive integer")
    destinations = plan.get("destinations")
    if not isinstance(destinations, list) or not destinations:
        fail("plugin release plan requires a GitHub Releases destination")
    ids: set[str] = set()
    source_ids: set[str] = set()
    for destination in destinations:
        if not isinstance(destination, dict) or destination.get("id") in ids:
            fail("plugin release destination IDs must be unique objects")
        validated = validate_destination(destination, False)
        ids.add(validated["id"])
        if validated["downloadSourceId"] in source_ids:
            fail("download source IDs must be unique across destinations")
        source_ids.add(validated["downloadSourceId"])
    validate_initial_release_destinations(destinations)
    catalog_destination = plan.get("catalogDestinationId")
    selected = next((item for item in destinations if item["id"] == catalog_destination), None)
    if selected is None or not selected.get("required"):
        fail("catalogDestinationId must select a required destination")
    plugins = plan.get("plugins")
    if not isinstance(plugins, list) or not plugins:
        fail("plugin release plan must contain at least one plugin")
    plugin_versions: set[tuple[str, str]] = set()
    base = plan_path.resolve().parent
    for plugin in plugins:
        if not isinstance(plugin, dict):
            fail("plugin release entries must be objects")
        artifact = plugin.get("artifact", {})
        identity = (plugin.get("id"), plugin.get("version"))
        if identity in plugin_versions:
            fail("plugin release ID/version pairs must be unique")
        plugin_versions.add(identity)
        for path_key, size_key, hash_key in (("path", "size", "sha256"),
                                              ("signaturePath", "signatureSize", "signatureSha256")):
            path = pathlib.Path(artifact.get(path_key, ""))
            if not path.is_absolute():
                path = (base / path).resolve()
            if not path.is_file():
                fail(f"plugin artifact input not found: {path}")
            if path.stat().st_size != artifact.get(size_key) or sha256_file(path) != artifact.get(hash_key):
                fail(f"plugin artifact size or SHA256 mismatch: {path.name}")
    catalog = plan.get("catalog")
    if require_catalog:
        if not isinstance(catalog, dict) or not pathlib.Path(catalog.get("path", "")).is_file():
            fail("signed catalog has not been generated; run publish first")
    return plan


def write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def create_plan(args: argparse.Namespace) -> None:
    packages_dir = pathlib.Path(args.packages_dir).resolve()
    private_key = pathlib.Path(args.private_key).expanduser().resolve()
    destinations_raw = plugin_package.strict_json_loads(pathlib.Path(args.destinations).read_text(encoding="utf-8"))
    if not isinstance(destinations_raw, list):
        fail("destinations file must contain a JSON array")
    destinations = [validate_destination(item, False) for item in destinations_raw]
    if len({item["id"] for item in destinations}) != len(destinations):
        fail("destination IDs must be unique")
    validate_initial_release_destinations(destinations)
    catalog_destination = next((item for item in destinations if item["id"] == args.catalog_destination), None)
    if not catalog_destination or not catalog_destination["required"]:
        fail("catalog destination must be a required destination")
    with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-public-key-") as directory:
        public_key = pathlib.Path(directory) / "public.pem"
        subprocess.run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        previous_versions = {}
        if args.previous_catalog:
            previous_public_key = pathlib.Path(args.previous_catalog_public_key).resolve() if args.previous_catalog_public_key else public_key
            previous_versions = previous_catalog_versions(pathlib.Path(args.previous_catalog).resolve(),
                                                          previous_public_key, args.channel)
        plugins = read_package_plugins(packages_dir, public_key, destinations, args.key_id, previous_versions)
    package_versions = {(item["id"], item["version"]): item for item in plugins}
    for identity, previous in previous_versions.items():
        current = package_versions.get(identity)
        if current is None:
            fail(f"package directory is not a complete catalog snapshot; missing {identity[0]} {identity[1]}")
        if current["artifact"]["sha256"] != previous["sha256"]:
            fail(f"published plugin version has changed bytes: {identity[0]} {identity[1]}")
    now = utc_now()
    git_commit = subprocess.run(["git", "rev-parse", "HEAD"], check=True, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.strip()
    plan_path = pathlib.Path(args.output).resolve()
    plan_path.parent.mkdir(parents=True, exist_ok=True)
    public_key_path = plan_path.with_name(f"{plan_path.stem}.{args.channel}-g{args.generation}.pub.pem")
    subprocess.run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key_path)],
                   check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    ledger_path = plan_path.parent / "plugin-catalog-generation-ledger.json"
    if ledger_path.is_file():
        ledger = load_json(ledger_path)
        previous = ledger.get("channels", {}).get(args.channel, {})
        if args.generation <= int(previous.get("generation", 0)):
            fail(f"catalog generation must exceed the last activated generation {previous['generation']}")
    plan = {
        "schemaVersion": 1, "kind": "plugin-release-plan", "channel": args.channel,
        "catalogGeneration": args.generation, "signingKeyId": args.key_id,
        "catalogDestinationId": args.catalog_destination,
        "createdAt": timestamp(now),
        "sourceCommit": git_commit, "destinations": destinations, "plugins": plugins,
        "catalogPublicKeyPath": str(public_key_path), "generationLedgerPath": str(ledger_path.resolve()),
        "catalog": None,
        "recordPath": str((plan_path.parent / f"plugin-release-record-{args.channel}-g{args.generation}.json").resolve()),
    }
    write_json(plan_path, plan)
    validate_plan(plan_path)
    print(f"plugin release plan ready: {len(plugins)} plugins, {len(destinations)} destinations, generation {args.generation}")


def static_request(destination: dict[str, Any], url: str, method: str,
                   input_path: pathlib.Path | None = None, output_path: pathlib.Path | None = None,
                   allow_404: bool = False) -> tuple[int, int, str] | None:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("https", "http") or not parsed.hostname:
        fail(f"invalid static provider URL: {url}")
    if parsed.scheme == "http" and not (destination.get("allowLocalHttp")
            and parsed.hostname in ("127.0.0.1", "::1", "localhost")):
        fail("plain HTTP is allowed only for the explicit loopback integration provider")
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    context = None
    if parsed.scheme == "https":
        if destination.get("caBundle"):
            context = ssl.create_default_context(cafile=destination["caBundle"])
        else:
            context = ssl.create_default_context()
        connection = http.client.HTTPSConnection(parsed.hostname, port, context=context, timeout=30)
    else:
        connection = http.client.HTTPConnection(parsed.hostname, port, timeout=30)
    headers = {"Connection": "close"}
    token_env = destination.get("tokenEnv")
    if token_env and method != "GET":
        token = os.environ.get(token_env)
        if not token:
            fail(f"required token environment variable {token_env} is empty")
        headers["Authorization"] = f"Bearer {token}"
    path = urllib.parse.urlunsplit(("", "", parsed.path or "/", parsed.query, ""))
    try:
        if input_path:
            headers["Content-Type"] = "application/octet-stream"
            headers["Content-Length"] = str(input_path.stat().st_size)
        connection.putrequest(method, path)
        for key, value in headers.items():
            connection.putheader(key, value)
        connection.endheaders()
        if input_path:
            with input_path.open("rb") as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    connection.send(chunk)
        response = connection.getresponse()
        if allow_404 and response.status == 404:
            response.read()
            return None
        if response.status < 200 or response.status >= 300:
            detail = response.read(1024).decode("utf-8", errors="replace")
            fail(f"static provider returned HTTP {response.status} for {method} {url}: {detail}")
        if output_path is not None:
            size, digest = sha256_stream(response, output_path)
        else:
            size, digest = sha256_stream(response)
        return response.status, size, digest
    except OSError as exc:
        fail(f"static provider request failed for {url}: {exc}")
    finally:
        connection.close()


def static_put_immutable(destination: dict[str, Any], url: str, path: pathlib.Path,
                         expected_size: int, expected_sha: str) -> None:
    existing = static_request(destination, url, "GET", allow_404=True)
    if existing is not None:
        _, size, digest = existing
        if (size, digest) != (expected_size, expected_sha):
            fail(f"immutable static asset already exists with different bytes: {url}")
        return
    static_request(destination, url, "PUT", input_path=path)
    result = static_request(destination, url, "GET")
    if result is None or result[1:] != (expected_size, expected_sha):
        fail(f"static provider readback mismatch: {url}")


def static_download(destination: dict[str, Any], url: str, path: pathlib.Path,
                    allow_404: bool = False) -> tuple[int, str] | None:
    result = static_request(destination, url, "GET", output_path=path, allow_404=allow_404)
    return (result[1], result[2]) if result else None


def static_activate(destination: dict[str, Any], pointer_url: str, catalog_path: pathlib.Path,
                    expected_size: int, expected_sha: str, work_dir: pathlib.Path) -> None:
    previous = work_dir / "previous-catalog.json"
    old = static_download(destination, pointer_url, previous, allow_404=True)
    try:
        static_request(destination, pointer_url, "PUT", input_path=catalog_path)
        current = static_request(destination, pointer_url, "GET")
        if current is None or current[1:] != (expected_size, expected_sha):
            fail(f"active catalog readback mismatch: {pointer_url}")
    except Exception as activation_error:
        try:
            if old is not None:
                static_request(destination, pointer_url, "PUT", input_path=previous)
                restored = static_request(destination, pointer_url, "GET")
                if restored is None or restored[1:] != old:
                    fail("old catalog pointer could not be restored after activation failure")
            else:
                static_request(destination, pointer_url, "DELETE")
                if static_request(destination, pointer_url, "GET", allow_404=True) is not None:
                    fail("new catalog pointer could not be removed after activation failure")
        except Exception as rollback_error:
            fail(f"catalog activation failed ({activation_error}); rollback also failed ({rollback_error})")
        raise


def gh(*args: str, capture: bool = True) -> str:
    if not shutil.which("gh"):
        fail("GitHub provider requires GitHub CLI (gh)")
    retries = 3 if args and args[0] == "api" and not any(
        item in ("-X", "--method") for item in args
    ) else 1
    transient_markers = ("eof", "connection reset", "connection refused", "tls handshake timeout",
                         "i/o timeout", "temporary failure", "unexpected eof")
    for attempt in range(retries):
        try:
            result = subprocess.run(["gh", *args], check=True, text=True,
                                    stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                                    stderr=subprocess.PIPE)
            return result.stdout.strip() if capture else ""
        except subprocess.CalledProcessError as exc:
            error = exc.stderr.strip()
            retryable = any(marker in error.lower() for marker in transient_markers)
            if attempt + 1 < retries and retryable:
                time.sleep(1 << attempt)
                continue
            fail(f"GitHub command failed: {' '.join(args[:3])}: {error}")


def github_public_download(destination: dict[str, Any], tag: str, filename: str,
                           target: pathlib.Path) -> None:
    url = f"https://github.com/{destination['repository']}/releases/download/{tag}/{urllib.parse.quote(filename)}"
    if not shutil.which("curl"):
        fail("GitHub public readback requires curl")
    transient_markers = ("ssl_error_syscall", "connection reset", "timed out", "unexpected eof", "empty reply")
    for attempt in range(3):
        try:
            subprocess.run(["curl", "--http1.1", "--fail", "--location", "--max-redirs", "3",
                            "--silent", "--show-error", url, "--output", str(target)],
                           check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            return
        except subprocess.CalledProcessError as exc:
            error = exc.stderr.decode(errors="replace").strip()
            retryable = any(marker in error.lower() for marker in transient_markers)
            if attempt + 1 < 3 and retryable:
                time.sleep(1 << attempt)
                continue
            fail(f"GitHub public asset readback failed for {filename}: {error}")


def github_release(destination: dict[str, Any], tag: str) -> dict[str, Any] | None:
    repo = destination["repository"]
    try:
        raw = gh("api", f"repos/{repo}/releases/tags/{tag}")
    except ReleaseError as exc:
        if "HTTP 404" in str(exc) or "Not Found" in str(exc):
            # GitHub's by-tag endpoint may hide a draft release even though it
            # is visible through the authenticated releases collection.
            selector = f'.[] | select(.tag_name == {json.dumps(tag)})'
            raw = gh("api", "--paginate", f"repos/{repo}/releases", "--jq", selector)
            if not raw:
                return None
        else:
            raise
    try:
        return plugin_package.strict_json_loads(raw)
    except Exception as exc:
        fail(f"GitHub returned invalid release metadata for {tag}: {exc}")


def github_asset_matches(destination: dict[str, Any], release: dict[str, Any],
                         name: str, path: pathlib.Path) -> bool:
    assets = release.get("assets", [])
    asset = next((item for item in assets if item.get("name") == name), None)
    if not asset:
        return False
    expected_size, expected_sha = path.stat().st_size, sha256_file(path)
    if asset.get("size") != expected_size:
        fail(f"GitHub immutable asset has conflicting size: {name}")
    if asset.get("digest") and asset["digest"] != f"sha256:{expected_sha}":
        fail(f"GitHub immutable asset has conflicting SHA256: {name}")
    # Draft assets are checked against GitHub's upload metadata here. Once the
    # version release is public, the same asset is downloaded and hashed below.
    if release.get("draft"):
        return True
    with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-gh-readback-") as directory:
        remote = pathlib.Path(directory) / name
        github_public_download(destination, release["tag_name"], name, remote)
        if not remote.is_file() or remote.stat().st_size != expected_size or sha256_file(remote) != expected_sha:
            fail(f"GitHub release readback does not match local asset: {name}")
    return True


def github_upload_asset(destination: dict[str, Any], tag: str, asset: pathlib.Path) -> None:
    repo = destination["repository"]
    retryable_markers = ("http 408", "timed out", "connection reset", "unexpected eof", "eof")
    for attempt in range(3):
        try:
            gh("release", "upload", tag, str(asset), "--repo", repo)
            return
        except ReleaseError as exc:
            if not any(marker in str(exc).lower() for marker in retryable_markers):
                raise
            # A timed-out upload may have reached GitHub. Re-read first; the
            # caller performs the full size/digest/readback verification later.
            release = github_release(destination, tag)
            if release and any(item.get("name") == asset.name for item in release.get("assets", [])):
                return
            if attempt == 2:
                raise
            time.sleep(1 << attempt)


def github_default_branch_commit(destination: dict[str, Any]) -> tuple[str, str]:
    repo = destination["repository"]
    try:
        repository = plugin_package.strict_json_loads(gh("api", f"repos/{repo}"))
    except Exception as exc:
        fail(f"could not read GitHub release repository metadata: {exc}")
    branch = repository.get("default_branch") if isinstance(repository, dict) else None
    if not isinstance(branch, str) or not branch:
        fail(f"GitHub release repository has no default branch: {repo}")
    encoded_branch = urllib.parse.quote(branch, safe="")
    try:
        commit = plugin_package.strict_json_loads(gh("api", f"repos/{repo}/commits/{encoded_branch}"))
    except ReleaseError as exc:
        if "HTTP 404" in str(exc) or "Not Found" in str(exc):
            fail(f"GitHub release repository is empty; initialize its default branch before publishing: {repo}")
        raise
    sha = commit.get("sha") if isinstance(commit, dict) else None
    if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
        fail(f"GitHub returned an invalid default-branch commit for {repo}")
    return branch, sha


def github_create_tag(destination: dict[str, Any], tag: str) -> tuple[str, str]:
    repo = destination["repository"]
    branch, sha = github_default_branch_commit(destination)
    gh("api", "--method", "POST", f"repos/{repo}/git/refs",
       "-f", f"ref=refs/tags/{tag}", "-f", f"sha={sha}")
    return branch, sha


def github_publish_release(destination: dict[str, Any], tag: str, title: str,
                           assets: list[pathlib.Path], publish: bool = True) -> None:
    repo = destination["repository"]
    release = github_release(destination, tag)
    if release is None:
        # A pre-existing tag without a release is immutable and must not be adopted.
        try:
            gh("api", f"repos/{repo}/git/ref/tags/{tag}")
            fail(f"GitHub tag exists without a matching release: {tag}")
        except ReleaseError as exc:
            if "HTTP 404" not in str(exc) and "Not Found" not in str(exc):
                raise
        github_create_tag(destination, tag)
        with tempfile.NamedTemporaryFile("w", encoding="utf-8") as notes:
            notes.write("Signed Dramatrix plugin package assets. This release is immutable.\n")
            notes.flush()
            gh("release", "create", tag, "--repo", repo, "--title", title,
               "--notes-file", notes.name, "--draft")
        release = github_release(destination, tag)
        if release is None:
            fail(f"GitHub draft release was not created: {tag}")
    elif not release.get("draft") and not release.get("published_at"):
        fail(f"GitHub release has an invalid lifecycle state: {tag}")

    verified_assets: set[str] = set()
    for asset in assets:
        name = asset.name
        existing = next((item for item in release.get("assets", []) if item.get("name") == name), None)
        if existing:
            if not github_asset_matches(destination, release, name, asset):
                fail(f"GitHub release asset did not match local bytes: {name}")
            verified_assets.add(name)
            continue
        if not release.get("draft"):
            fail(f"published GitHub release is missing immutable asset {name}")
        github_upload_asset(destination, tag, asset)

    release = github_release(destination, tag) or {}
    for asset in assets:
        if asset.name not in verified_assets and not github_asset_matches(destination, release, asset.name, asset):
            fail(f"GitHub release did not read back asset {asset.name}")
    if publish and release.get("draft"):
        gh("api", "--method", "PATCH", f"repos/{repo}/releases/{release['id']}", "-F", "draft=false")
        release = github_release(destination, tag) or {}
        if release.get("draft"):
            fail(f"GitHub release did not activate: {tag}")
        for asset in assets:
            github_asset_matches(destination, release, asset.name, asset)


def github_ensure_channel_release(destination: dict[str, Any], tag: str) -> dict[str, Any]:
    repo = destination["repository"]
    release = github_release(destination, tag)
    if release is None:
        gh("release", "create", tag, "--repo", repo, "--title", f"Dramatrix plugin catalog {tag}",
           "--notes", "Mutable pointer to the latest signed plugin catalog.")
        release = github_release(destination, tag)
    if not release or release.get("draft") or release.get("immutable") is True:
        fail(f"GitHub catalog pointer release is unavailable or immutable: {tag}")
    return release


def github_stage_catalog_asset(destination: dict[str, Any], tag: str, release: dict[str, Any],
                              catalog_path: pathlib.Path, staging_name: str,
                              expected_size: int, expected_sha: str) -> dict[str, Any]:
    repo = destination["repository"]

    def find_asset(document: dict[str, Any], name: str) -> dict[str, Any] | None:
        return next((item for item in document.get("assets", []) if item.get("name") == name), None)

    def assert_matches(asset: dict[str, Any], name: str) -> None:
        if asset.get("size") != expected_size or (
                asset.get("digest") and asset["digest"] != f"sha256:{expected_sha}"):
            fail(f"GitHub catalog staging asset conflicts with this generation: {name}")

    pending = find_asset(release, staging_name)
    if pending:
        assert_matches(pending, staging_name)
        return pending

    uploaded = find_asset(release, catalog_path.name)
    if not uploaded:
        # Avoid the path#display-name syntax, which older gh versions ignore.
        github_upload_asset(destination, tag, catalog_path)
        release = github_release(destination, tag) or {}
        uploaded = find_asset(release, catalog_path.name)
    if not uploaded:
        fail(f"GitHub catalog upload did not create asset: {catalog_path.name}")
    assert_matches(uploaded, catalog_path.name)
    gh("api", "--method", "PATCH", f"repos/{repo}/releases/assets/{uploaded['id']}",
       "-F", f"name={staging_name}")
    release = github_release(destination, tag) or {}
    pending = find_asset(release, staging_name)
    if not pending:
        fail(f"GitHub catalog staging asset could not be named: {staging_name}")
    assert_matches(pending, staging_name)
    return pending


def github_activate(destination: dict[str, Any], channel: str, generation: int,
                    catalog_path: pathlib.Path, expected_size: int, expected_sha: str,
                    work_dir: pathlib.Path) -> None:
    repo = destination["repository"]
    tag = f"plugin-catalog-{channel}"
    release = github_ensure_channel_release(destination, tag)
    pointer = f"catalog-{channel}.json"
    temp_name = f"catalog-{channel}.pending-{generation}.json"
    backup_name = f"catalog-{channel}.previous-{generation}.json"
    existing = github_release(destination, tag) or release
    assets = existing.get("assets", [])
    old_asset = next((item for item in assets if item.get("name") == pointer), None)
    old_sha = None
    if old_asset:
        with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-old-catalog-") as directory:
            old_snapshot = pathlib.Path(directory) / pointer
            github_public_download(destination, tag, pointer, old_snapshot)
            old_sha = sha256_file(old_snapshot)
    github_stage_catalog_asset(destination, tag, existing, catalog_path, temp_name,
                               expected_size, expected_sha)
    with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-catalog-readback-") as directory:
        staged = pathlib.Path(directory) / temp_name
        github_public_download(destination, tag, temp_name, staged)
        if staged.stat().st_size != expected_size or sha256_file(staged) != expected_sha:
            fail("GitHub staged catalog readback mismatch")
    current = github_release(destination, tag) or {}
    staged_asset = next((item for item in current.get("assets", []) if item.get("name") == temp_name), None)
    if not staged_asset:
        fail("GitHub catalog staging asset disappeared")
    if old_asset:
        gh("api", "--method", "PATCH", f"repos/{repo}/releases/assets/{old_asset['id']}", "-F", f"name={backup_name}")
    try:
        gh("api", "--method", "PATCH", f"repos/{repo}/releases/assets/{staged_asset['id']}", "-F", f"name={pointer}")
        with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-catalog-pointer-") as directory:
            active = pathlib.Path(directory) / pointer
            github_public_download(destination, tag, pointer, active)
            if active.stat().st_size != expected_size or sha256_file(active) != expected_sha:
                fail("GitHub active catalog pointer readback mismatch")
    except Exception:
        latest = github_release(destination, tag) or {}
        active_asset = next((item for item in latest.get("assets", []) if item.get("name") == pointer), None)
        if active_asset and active_asset.get("id") == staged_asset.get("id"):
            gh("api", "--method", "PATCH", f"repos/{repo}/releases/assets/{staged_asset['id']}", "-F", f"name={temp_name}")
        if old_asset:
            gh("api", "--method", "PATCH", f"repos/{repo}/releases/assets/{old_asset['id']}", "-F", f"name={pointer}")
        if old_asset and old_sha:
            with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-catalog-rollback-") as directory:
                restored = pathlib.Path(directory) / pointer
                github_public_download(destination, tag, pointer, restored)
                if not restored.is_file() or sha256_file(restored) != old_sha:
                    fail("old GitHub catalog pointer could not be restored after activation failure")
        elif not old_asset:
            latest = github_release(destination, tag) or {}
            if any(item.get("name") == pointer for item in latest.get("assets", [])):
                fail("new GitHub catalog pointer could not be removed after activation failure")
        raise
    if old_asset:
        gh("api", "--method", "DELETE", f"repos/{repo}/releases/assets/{old_asset['id']}")


def asset_tag(plugin: dict[str, Any]) -> str:
    return f"plugin-{plugin['id'].split('/', 1)[1]}-v{plugin['version']}"


def provider_upload_artifacts(destination: dict[str, Any], plugins: list[dict[str, Any]], plan: dict[str, Any]) -> None:
    if destination["provider"] == "github-release":
        for plugin in plugins:
            artifact = plugin["artifact"]
            github_publish_release(destination, asset_tag(plugin), f"Dramatrix plugin {plugin['id']} {plugin['version']}",
                                   [pathlib.Path(artifact["path"]), pathlib.Path(artifact["signaturePath"])])
        return
    destination = {**destination, "allowLocalHttp": plan.get("allowLocalHttp", False)}
    for plugin in plugins:
        artifact = plugin["artifact"]
        for file_key, size_key, hash_key, url_key in (
            ("path", "size", "sha256", "sourceUrls"),
            ("signaturePath", "signatureSize", "signatureSha256", "signatureSourceUrls"),
        ):
            path = pathlib.Path(artifact[file_key])
            url = artifact[url_key][destination["id"]]
            static_put_immutable(destination, url, path, artifact[size_key], artifact[hash_key])


def provider_verify_artifacts(destination: dict[str, Any], plugins: list[dict[str, Any]], plan: dict[str, Any]) -> None:
    if destination["provider"] == "github-release":
        for plugin in plugins:
            release = github_release(destination, asset_tag(plugin))
            if not release or release.get("draft"):
                fail(f"GitHub plugin release is not publicly activated: {asset_tag(plugin)}")
            artifact = plugin["artifact"]
            for key in ("path", "signaturePath"):
                path = pathlib.Path(artifact[key])
                if not github_asset_matches(destination, release, path.name, path):
                    fail(f"GitHub release is missing verified asset: {path.name}")
        return
    destination = {**destination, "allowLocalHttp": plan.get("allowLocalHttp", False)}
    for plugin in plugins:
        artifact = plugin["artifact"]
        for size_key, hash_key, url_key in (("size", "sha256", "sourceUrls"),
                                            ("signatureSize", "signatureSha256", "signatureSourceUrls")):
            result = static_request(destination, artifact[url_key][destination["id"]], "GET")
            if result is None or result[1:] != (artifact[size_key], artifact[hash_key]):
                fail(f"static target verification failed for {plugin['id']}")


def publish_catalog(destination: dict[str, Any], plan: dict[str, Any], catalog: dict[str, Any]) -> None:
    path = pathlib.Path(catalog["path"])
    if destination["provider"] == "github-release":
        tag = catalog["immutableTag"]
        github_publish_release(destination, tag, f"Dramatrix {plan['channel']} plugin catalog {plan['catalogGeneration']}",
                               [path])
        with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-catalog-verify-") as directory:
            remote = pathlib.Path(directory) / path.name
            github_public_download(destination, tag, path.name, remote)
            if remote.stat().st_size != catalog["size"] or sha256_file(remote) != catalog["sha256"]:
                fail("GitHub immutable catalog readback mismatch")
    else:
        destination = {**destination, "allowLocalHttp": plan.get("allowLocalHttp", False)}
        static_put_immutable(destination, catalog["immutableUrl"], path, catalog["size"], catalog["sha256"])


def verify_catalog_remote(destination: dict[str, Any], plan: dict[str, Any], catalog: dict[str, Any]) -> None:
    path = pathlib.Path(catalog["path"])
    if destination["provider"] == "github-release":
        release = github_release(destination, catalog["immutableTag"])
        if not release or release.get("draft"):
            fail("GitHub immutable catalog release is not published")
        if not github_asset_matches(destination, release, path.name, path):
            fail("GitHub immutable catalog asset could not be verified")
    else:
        destination = {**destination, "allowLocalHttp": plan.get("allowLocalHttp", False)}
        result = static_request(destination, catalog["immutableUrl"], "GET")
        if result is None or result[1:] != (catalog["size"], catalog["sha256"]):
            fail("static immutable catalog readback mismatch")


def load_record(plan: dict[str, Any]) -> dict[str, Any]:
    path = pathlib.Path(plan["recordPath"])
    if path.is_file():
        return load_json(path)
    return {"schemaVersion": 1, "planPath": None, "destinations": {}, "catalog": None,
            "pluginIds": [item["id"] for item in plan["plugins"]],
            "sourceCommit": plan.get("sourceCommit"), "channel": plan["channel"],
            "catalogGeneration": plan["catalogGeneration"]}


def save_record(plan: dict[str, Any], record: dict[str, Any]) -> None:
    write_json(pathlib.Path(plan["recordPath"]), record)


def verify_plan_signatures(plan: dict[str, Any], public_key: pathlib.Path) -> None:
    for plugin in plan["plugins"]:
        artifact = plugin["artifact"]
        archive = pathlib.Path(artifact["path"])
        sidecar = pathlib.Path(artifact["signaturePath"])
        package = plugin_package.verify_signature(archive, public_key)
        signature_envelope = load_json(sidecar)
        checks = {
            "id": plugin["id"], "version": plugin["version"], "type": plugin["type"],
            "publisher": plugin["publisher"], "innerManifest": plugin["innerManifest"],
            "compatibility": plugin["compatibility"], "runtime": plugin.get("runtime"),
        }
        for key, expected in checks.items():
            if package.get(key) != expected:
                fail(f"release plan metadata differs from signed package manifest: {plugin['id']} ({key})")
        if signature_envelope.get("signature") != artifact.get("signature"):
            fail(f"release plan detached signature metadata changed: {plugin['id']}")
        if artifact["signature"].get("keyId") != plan["signingKeyId"]:
            fail(f"plugin artifact signing key differs from catalog key: {plugin['id']}")


def command_validate(plan_path: pathlib.Path, require_catalog: bool = False) -> dict[str, Any]:
    plan = validate_plan(plan_path, require_catalog=require_catalog)
    public_key = pathlib.Path(plan["catalogPublicKeyPath"])
    verify_plan_signatures(plan, public_key)
    if plan.get("catalog"):
        catalog = plan["catalog"]
        catalog_path = pathlib.Path(catalog["path"])
        if not catalog_path.is_file() or catalog_path.stat().st_size != catalog.get("size") \
                or sha256_file(catalog_path) != catalog.get("sha256"):
            fail("signed catalog file does not match its release plan size and digest")
        payload = verify_catalog(catalog_path, public_key)
        if payload.get("generation") != plan["catalogGeneration"] or payload.get("channel") != plan["channel"]:
            fail("signed catalog generation or channel differs from release plan")
        envelope = load_json(catalog_path)
        if envelope["signature"].get("keyId") != plan["signingKeyId"]:
            fail("catalog signing key ID differs from release plan")
    print(f"plugin release plan valid: {len(plan['plugins'])} plugin versions, {len(plan['destinations'])} destinations")
    return plan


def command_publish(plan_path: pathlib.Path, private_key: pathlib.Path) -> None:
    plan = command_validate(plan_path)
    with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-release-key-") as directory:
        public_key = pathlib.Path(directory) / "public.pem"
        subprocess.run(["openssl", "pkey", "-in", str(private_key), "-pubout", "-out", str(public_key)],
                       check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if public_key.read_bytes() != pathlib.Path(plan["catalogPublicKeyPath"]).read_bytes():
            fail("plugin signing private key does not match the public key frozen in this plan")
    record = load_record(plan)
    record["planPath"] = str(plan_path.resolve())
    record["plugins"] = []
    for plugin in plan["plugins"]:
        artifact = plugin["artifact"]
        record["plugins"].append({"pluginId": plugin["id"], "version": plugin["version"],
                                   "tag": asset_tag(plugin),
                                   "sha256": artifact["sha256"], "signatureKeyId": artifact["signature"]["keyId"],
                                   "destinations": {}})
    failures: list[str] = []
    verified: set[str] = set()
    for destination in plan["destinations"]:
        state = {"provider": destination["provider"], "required": destination["required"],
                 "publish": "running", "verify": "pending"}
        record["destinations"][destination["id"]] = state
        save_record(plan, record)
        try:
            provider_upload_artifacts(destination, plan["plugins"], plan)
            provider_verify_artifacts(destination, plan["plugins"], plan)
            state["publish"] = "succeeded"
            state["verify"] = "succeeded"
            state["verifiedAt"] = timestamp(utc_now())
            verified.add(destination["id"])
            for plugin_record, plugin in zip(record["plugins"], plan["plugins"]):
                artifact = plugin["artifact"]
                plugin_record["destinations"][destination["id"]] = {
                    "downloadSourceId": destination["downloadSourceId"],
                    "url": artifact["sourceUrls"][destination["id"]],
                    "archiveSize": artifact["size"], "archiveSha256": artifact["sha256"],
                    "signatureFileName": pathlib.Path(artifact["signaturePath"]).name,
                    "signatureSha256": artifact["signatureSha256"], "readback": "verified",
                }
        except Exception as exc:
            state["publish"] = "failed"
            state["verify"] = "failed"
            state["error"] = str(exc)
            failures.append(f"{destination['id']}: {exc}")
            save_record(plan, record)
            if destination["required"]:
                break
        save_record(plan, record)
    required_ids = {dest["id"] for dest in plan["destinations"] if dest["required"]}
    if not required_ids.issubset(verified):
        fail("required plugin destination publish/readback failed; catalog was not activated: " + "; ".join(failures))
    catalog_dest = next(dest for dest in plan["destinations"] if dest["id"] == plan["catalogDestinationId"])
    if plan.get("catalog"):
        catalog = plan["catalog"]
        catalog_path = pathlib.Path(catalog["path"])
        if not catalog_path.is_file() or sha256_file(catalog_path) != catalog.get("sha256"):
            fail("existing signed catalog bytes changed; refuse to reuse this generation")
        payload = verify_catalog(catalog_path, pathlib.Path(plan["catalogPublicKeyPath"]))
        if payload.get("generation") != plan["catalogGeneration"] or payload.get("channel") != plan["channel"]:
            fail("existing signed catalog generation/channel differs from plan")
    else:
        now = utc_now()
        payload = build_catalog_payload(plan["plugins"], plan["destinations"], plan["channel"],
                                        plan["catalogGeneration"], verified, now)
        signature = openssl_sign(b"Dramatrix-Plugin-Catalog-V1\n" + canonical(payload), private_key)
        envelope = {"payload": payload, "signature": {
            "algorithm": "Ed25519", "keyId": plan["signingKeyId"],
            "value": base64.urlsafe_b64encode(signature).decode("ascii").rstrip("="),
        }}
        catalog_path = plan_path.parent / f"catalog-{plan['channel']}-{plan['catalogGeneration']}.json"
        write_json(catalog_path, envelope)
        immutable_url, pointer_url = catalog_paths(catalog_dest, plan["channel"], plan["catalogGeneration"])
        immutable_tag = f"plugin-catalog-{plan['channel']}-g{plan['catalogGeneration']}"
        catalog = {"path": str(catalog_path.resolve()), "fileName": catalog_path.name,
                   "size": catalog_path.stat().st_size, "sha256": sha256_file(catalog_path),
                   "immutableUrl": immutable_url, "pointerUrl": pointer_url,
                   "immutableTag": immutable_tag, "destinationId": catalog_dest["id"],
                   "verifiedDestinationIds": sorted(verified),
                   "generatedAt": payload["generatedAt"], "expiresAt": payload["expiresAt"]}
        payload = verify_catalog(catalog_path, pathlib.Path(plan["catalogPublicKeyPath"]))
        plan["catalog"] = catalog
        write_json(plan_path, plan)
    try:
        publish_catalog(catalog_dest, plan, catalog)
        verify_catalog_remote(catalog_dest, plan, catalog)
        record["destinations"][catalog_dest["id"]]["catalogPublish"] = "succeeded"
        record["destinations"][catalog_dest["id"]]["catalogVerify"] = "succeeded"
    except Exception as exc:
        record["destinations"][catalog_dest["id"]]["catalogPublish"] = "failed"
        record["destinations"][catalog_dest["id"]]["catalogError"] = str(exc)
        save_record(plan, record)
        fail(f"catalog was not activated because its immutable upload/readback failed: {exc}")
    previous_catalog = record.get("catalog") or {}
    catalog_status = "activated" if previous_catalog.get("status") == "activated" and previous_catalog.get("sha256") == catalog["sha256"] else "verified_not_activated"
    record["catalog"] = {**catalog, "status": catalog_status,
                          "generation": plan["catalogGeneration"],
                          "signatureKeyId": plan["signingKeyId"], "tag": catalog["immutableTag"]}
    save_record(plan, record)
    print(f"published and read back {len(plan['plugins'])} plugin packages to {len(verified)} destinations; catalog generation {plan['catalogGeneration']} is ready for activation")


def command_verify(plan_path: pathlib.Path) -> None:
    plan = command_validate(plan_path, require_catalog=True)
    record = load_record(plan)
    if not record.get("catalog") or record["catalog"].get("status") not in ("verified_not_activated", "activated"):
        fail("publish the signed immutable catalog successfully before remote verification")
    verified: list[str] = []
    for destination in plan["destinations"]:
        if not destination["required"] and record.get("destinations", {}).get(destination["id"], {}).get("verify") != "succeeded":
            continue
        try:
            provider_verify_artifacts(destination, plan["plugins"], plan)
            verified.append(destination["id"])
        except Exception as exc:
            if destination["required"]:
                raise
            record["destinations"][destination["id"]]["verify"] = "failed"
            record["destinations"][destination["id"]]["error"] = str(exc)
    catalog_dest = next(item for item in plan["destinations"] if item["id"] == plan["catalogDestinationId"])
    verify_catalog_remote(catalog_dest, plan, plan["catalog"])
    catalog_payload = verify_catalog(pathlib.Path(plan["catalog"]["path"]), pathlib.Path(plan["catalogPublicKeyPath"]))
    if catalog_payload.get("generation") != plan["catalogGeneration"] or catalog_payload.get("channel") != plan["channel"]:
        fail("signed catalog generation or channel does not match release plan")
    if record["catalog"].get("status") != "activated":
        record["catalog"]["status"] = "verified_not_activated"
    record["catalog"]["lastVerifiedAt"] = timestamp(utc_now())
    save_record(plan, record)
    print("plugin archives and immutable catalog passed remote readback")


def command_activate(plan_path: pathlib.Path) -> None:
    plan = command_validate(plan_path, require_catalog=True)
    record = load_record(plan)
    if not record.get("catalog") or record["catalog"].get("status") not in ("verified_not_activated", "activated"):
        fail("run verify successfully before catalog activation")
    catalog_dest = next(item for item in plan["destinations"] if item["id"] == plan["catalogDestinationId"])
    catalog = plan["catalog"]
    verify_catalog_remote(catalog_dest, plan, catalog)
    public_key = pathlib.Path(plan["catalogPublicKeyPath"])
    payload = verify_catalog(pathlib.Path(catalog["path"]), public_key)
    if payload.get("generation") != plan["catalogGeneration"] or payload.get("channel") != plan["channel"]:
        fail("signed catalog generation or channel does not match release plan")
    ledger_path = pathlib.Path(plan["generationLedgerPath"])
    ledger = load_json(ledger_path) if ledger_path.is_file() else {"schemaVersion": 1, "channels": {}}
    previous_local = ledger.get("channels", {}).get(plan["channel"], {})
    previous_local_generation = int(previous_local.get("generation", 0))
    if plan["catalogGeneration"] < previous_local_generation:
        fail(f"catalog generation is lower than the last local activation {previous_local_generation}")
    if plan["catalogGeneration"] == previous_local_generation and previous_local.get("sha256") != catalog["sha256"]:
        fail("catalog generation already exists locally with different bytes")
    already_active = False
    with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-catalog-current-") as directory:
        previous_path = pathlib.Path(directory) / "previous-catalog.json"
        previous_generation = None
        previous_sha = None
        if catalog_dest["provider"] == "github-release":
            release = github_release(catalog_dest, f"plugin-catalog-{plan['channel']}")
            if release:
                pointer = f"catalog-{plan['channel']}.json"
                if any(item.get("name") == pointer for item in release.get("assets", [])):
                    previous_path = pathlib.Path(directory) / pointer
                    github_public_download(catalog_dest, release["tag_name"], pointer, previous_path)
                    previous_payload = verify_catalog(previous_path, public_key)
                    previous_generation = previous_payload.get("generation")
                    previous_sha = sha256_file(previous_path)
        else:
            current = {**catalog_dest, "allowLocalHttp": plan.get("allowLocalHttp", False)}
            old_result = static_download(current, catalog["pointerUrl"], previous_path, allow_404=True)
            if old_result is not None:
                previous_payload = verify_catalog(previous_path, public_key)
                previous_generation = previous_payload.get("generation")
                previous_sha = old_result[1]
        if previous_generation is not None and int(previous_generation) > plan["catalogGeneration"]:
            fail(f"catalog generation must exceed the active remote generation {previous_generation}")
        if previous_generation is not None and int(previous_generation) == plan["catalogGeneration"]:
            if previous_sha != catalog["sha256"]:
                fail("active catalog generation already exists with different bytes")
            already_active = True
    if not already_active:
        if catalog_dest["provider"] == "github-release":
            github_activate(catalog_dest, plan["channel"], plan["catalogGeneration"], pathlib.Path(catalog["path"]),
                            catalog["size"], catalog["sha256"], pathlib.Path(tempfile.gettempdir()))
        else:
            destination = {**catalog_dest, "allowLocalHttp": plan.get("allowLocalHttp", False)}
            with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-catalog-activate-") as directory:
                static_activate(destination, catalog["pointerUrl"], pathlib.Path(catalog["path"]),
                                catalog["size"], catalog["sha256"], pathlib.Path(directory))
    record["catalog"]["status"] = "activated"
    record["catalog"]["activatedAt"] = timestamp(utc_now())
    record["catalog"]["generation"] = plan["catalogGeneration"]
    record["catalog"]["destinationId"] = catalog_dest["id"]
    record["sourceCommit"] = plan.get("sourceCommit")
    save_record(plan, record)
    ledger.setdefault("channels", {})[plan["channel"]] = {
        "generation": plan["catalogGeneration"], "sha256": catalog["sha256"],
        "catalogUrl": catalog["pointerUrl"], "activatedAt": record["catalog"]["activatedAt"],
    }
    write_json(ledger_path, ledger)
    print(f"activated catalog generation {plan['catalogGeneration']} on {catalog_dest['id']}")


def command_run(plan_path: pathlib.Path, private_key: pathlib.Path) -> None:
    command_publish(plan_path, private_key)
    command_verify(plan_path)
    command_activate(plan_path)


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    sub = result.add_subparsers(dest="command", required=True)
    create = sub.add_parser("create-plan", help="create a plugin-only release plan from signed archives")
    create.add_argument("--packages-dir", required=True)
    create.add_argument("--destinations", required=True, help="JSON array of release destinations")
    create.add_argument("--catalog-destination", required=True)
    create.add_argument("--channel", choices=("stable", "beta"), required=True)
    create.add_argument("--generation", type=int, required=True)
    create.add_argument("--key-id", required=True)
    create.add_argument("--private-key", help="defaults to DRAMATRIX_PLUGIN_SIGNING_KEY_PEM")
    create.add_argument("--previous-catalog", help="preserve publishedAt/status from the current signed catalog")
    create.add_argument("--previous-catalog-public-key", help="defaults to the current signing key's public key")
    create.add_argument("--output", required=True)
    for name in ("validate", "publish", "verify", "activate", "run"):
        command = sub.add_parser(name)
        command.add_argument("--plan", required=True)
        if name in ("publish", "run"):
            command.add_argument("--private-key", help="defaults to DRAMATRIX_PLUGIN_SIGNING_KEY_PEM")
    return result


def main() -> int:
    args = parser().parse_args()
    try:
        if args.command == "create-plan":
            with signing_key_file(args.private_key) as key_path:
                args.private_key = str(key_path)
                create_plan(args)
        elif args.command == "validate":
            command_validate(pathlib.Path(args.plan).resolve())
        elif args.command == "publish":
            with signing_key_file(args.private_key) as key_path:
                command_publish(pathlib.Path(args.plan).resolve(), key_path)
        elif args.command == "verify":
            command_verify(pathlib.Path(args.plan).resolve())
        elif args.command == "activate":
            command_activate(pathlib.Path(args.plan).resolve())
        elif args.command == "run":
            with signing_key_file(args.private_key) as key_path:
                command_run(pathlib.Path(args.plan).resolve(), key_path)
        return 0
    except (ReleaseError, plugin_package.PackageError, subprocess.CalledProcessError,
            OSError, ValueError, KeyError, TypeError) as exc:
        print(f"plugin-release: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
