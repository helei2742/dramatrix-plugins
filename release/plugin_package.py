#!/usr/bin/env python3
"""Build and verify deterministic, signed Dramatrix plugin archives."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import pathlib
import re
import stat
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from typing import Any


MAX_ARCHIVE_BYTES = 2 * 1024**3
MAX_EXPANDED_BYTES = 8 * 1024**3
MAX_ENTRY_BYTES = 2 * 1024**3
MAX_ENTRIES = 20_000
MAX_EXPANSION_RATIO = 200
MAX_PATH_LENGTH = 512
PUBLISHER = "dramatrix"
DEFAULT_BACKEND_RANGE = ">=1.0.2 <2.0.0"
DEFAULT_PROFILE_API_RANGE = "^1.0.0"
SEMVER = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-((?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
    r"(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
SHORT_SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)(?:\.(0|[1-9]\d*))?$")
PLUGIN_ID = re.compile(r"^dramatrix/[a-z0-9][a-z0-9.-]*$")


class PackageError(Exception):
    pass


def unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise PackageError(f"duplicate JSON property: {key}")
        result[key] = value
    return result


def strict_json_loads(text: str) -> Any:
    return json.loads(text, object_pairs_hook=unique_json_object)


@dataclass(frozen=True)
class PluginSource:
    module: pathlib.Path
    plugin_type: str
    manifest_path: pathlib.Path


def fail(message: str) -> "NoReturn":
    raise PackageError(message)


def read_json(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = strict_json_loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # pragma: no cover - exact decoder exception varies
        fail(f"invalid JSON in {path}: {exc}")
    if not isinstance(value, dict):
        fail(f"expected a JSON object in {path}")
    return value


def jcs_value(value: Any) -> Any:
    """Normalize the protocol's JSON subset for RFC 8785 canonical encoding."""
    if isinstance(value, str):
        try:
            value.encode("utf-8")
        except UnicodeEncodeError:
            fail("JCS strings must contain valid Unicode scalar values")
        return value
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, int):
        if abs(value) > (2**53 - 1):
            fail("JCS integer exceeds the exact IEEE-754 integer range")
        return value
    if isinstance(value, float):
        fail("floating-point values are not permitted in signed plugin metadata")
    if isinstance(value, list):
        return [jcs_value(item) for item in value]
    if isinstance(value, dict):
        if any(not isinstance(key, str) for key in value):
            fail("JCS object keys must be strings")
        try:
            for key in value:
                key.encode("utf-16-be")
        except UnicodeEncodeError:
            fail("JCS object keys must contain valid Unicode scalar values")
        # RFC 8785 orders property names by UTF-16 code units.
        return {
            key: jcs_value(value[key])
            for key in sorted(value, key=lambda item: item.encode("utf-16-be"))
        }
    fail(f"unsupported JCS value type: {type(value).__name__}")


def jcs_bytes(value: Any) -> bytes:
    return json.dumps(
        jcs_value(value), ensure_ascii=False, separators=(",", ":"), allow_nan=False
    ).encode("utf-8")


def normalize_version(value: Any) -> str:
    version = str(value).strip()
    if SHORT_SEMVER.fullmatch(version):
        parts = version.split(".")
        if len(parts) == 2:
            version += ".0"
    if not SEMVER.fullmatch(version):
        fail(f"plugin version must be SemVer 2.0.0: {value!r}")
    return version


def normalize_api_range(value: Any) -> str:
    text = str(value or "").strip()
    if not text:
        fail("plugin API compatibility range is required")
    if re.fullmatch(r"\^\d+", text):
        return f"^{text[1:]}.0.0"
    if re.fullmatch(r"~\d+\.\d+", text):
        return f"~{text[1:]}.0"
    return text


def module_for(manifest: pathlib.Path) -> pathlib.Path:
    for parent in manifest.parents:
        if (parent / "pom.xml").is_file():
            return parent
    fail(f"could not find a module pom.xml for {manifest}")


def discover_plugins(project_root: pathlib.Path) -> list[PluginSource]:
    roots = (
        ("management", "dramatrix-management-plugin", "**/src/main/plugin-package/management-plugin.json"),
        ("media", "dramatrix-plugin", "**/src/main/java/**/adapter.json"),
        ("profile", "dramatrix-plugin/profile", "**/src/main/profile/plugin.json"),
    )
    found: list[PluginSource] = []
    for plugin_type, root_name, pattern in roots:
        for manifest in sorted((project_root / root_name).glob(pattern)):
            if manifest.name.endswith(".bak"):
                continue
            found.append(PluginSource(module_for(manifest), plugin_type, manifest))
    return found


def safe_archive_path(name: str) -> str:
    if not name or len(name) > MAX_PATH_LENGTH:
        fail(f"invalid or overlong archive path: {name!r}")
    if "\\" in name or "\x00" in name or any(ord(char) < 32 for char in name):
        fail(f"archive paths must be safe slash-separated UTF-8 paths: {name!r}")
    path = pathlib.PurePosixPath(name)
    windows = pathlib.PureWindowsPath(name)
    if path.is_absolute() or windows.is_absolute() or windows.drive:
        fail(f"absolute archive path is forbidden: {name!r}")
    if any(part in ("", ".", "..") for part in path.parts):
        fail(f"non-normalized or parent archive path is forbidden: {name!r}")
    normalized = path.as_posix()
    if normalized != name:
        fail(f"archive path is not normalized: {name!r}")
    return normalized


PackageEntry = bytes | pathlib.Path


def add_source_file(entries: dict[str, PackageEntry], name: str, source: pathlib.Path) -> None:
    name = safe_archive_path(name)
    if source.is_symlink() or not source.is_file():
        fail(f"package inputs must be regular files, not symlinks or special files: {source}")
    if name in entries:
        fail(f"duplicate package path: {name}")
    folded = name.casefold()
    if any(existing.casefold() == folded for existing in entries):
        fail(f"case-insensitive package path collision: {name}")
    size = source.stat().st_size
    if size > MAX_ENTRY_BYTES:
        fail(f"package file exceeds the per-file limit: {source}")
    entries[name] = source


def add_tree(entries: dict[str, PackageEntry], root: pathlib.Path, archive_root: str = "") -> None:
    if not root.is_dir():
        fail(f"package source directory not found: {root}")
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            fail(f"package source contains a symlink: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(root).as_posix()
        name = f"{archive_root}/{relative}" if archive_root else relative
        add_source_file(entries, name, path)


def pom_property(project_root: pathlib.Path, name: str) -> str | None:
    text = (project_root / "pom.xml").read_text(encoding="utf-8")
    match = re.search(rf"<{re.escape(name)}>\s*([^<]+?)\s*</{re.escape(name)}>", text)
    return match.group(1).strip() if match else None


def root_revision(project_root: pathlib.Path) -> str:
    value = pom_property(project_root, "dramatrix.backend.version")
    if value is None or value.startswith("${"):
        value = pom_property(project_root, "revision")
    if value is None:
        fail("root pom.xml has neither <dramatrix.backend.version> nor <revision>")
    return normalize_version(value)


def plugin_api_version(project_root: pathlib.Path) -> str:
    value = pom_property(project_root, "plugin.api.version")
    if value is not None:
        return normalize_version(value)
    # Keep compatibility with older source roots that still used the backend revision.
    return root_revision(project_root)


def validate_inner_package(
    entries: dict[str, PackageEntry | None], package: dict[str, Any]
) -> None:
    if package.get("schemaVersion") != 1 or package.get("publisher") != PUBLISHER:
        fail("unsupported package schema or publisher")
    try:
        if normalize_version(package.get("version")) != package.get("version"):
            fail("package version is not canonical SemVer")
    except PackageError:
        raise
    compatibility = package.get("compatibility")
    if not isinstance(compatibility, dict) or not compatibility.get("backendVersionRange"):
        fail("package compatibility metadata is incomplete")
    api = compatibility.get("pluginApi")
    if not isinstance(api, dict) or api.get("namespace") not in (
        "management-api", "media-plugin-api", "profile-manifest-schema"
    ) or not api.get("range"):
        fail("package plugin API compatibility metadata is incomplete")
    inner_path = safe_archive_path(package.get("innerManifest", ""))
    if inner_path not in entries or entries[inner_path] is None:
        fail(f"package inner manifest is missing: {inner_path}")
    try:
        inner_data = entries[inner_path]
        if isinstance(inner_data, pathlib.Path):
            if inner_data.stat().st_size > 4 * 1024**2:
                fail(f"package inner manifest is too large: {inner_path}")
            inner_data = inner_data.read_bytes()
        elif len(inner_data) > 4 * 1024**2:  # type: ignore[arg-type]
            fail(f"package inner manifest is too large: {inner_path}")
        inner = strict_json_loads(inner_data.decode("utf-8"))  # type: ignore[union-attr]
    except PackageError:
        raise
    except Exception as exc:
        fail(f"invalid inner manifest {inner_path}: {exc}")
    if not isinstance(inner, dict):
        fail(f"inner manifest must be a JSON object: {inner_path}")
    plugin_id = package.get("id")
    if not isinstance(plugin_id, str) or not PLUGIN_ID.fullmatch(plugin_id):
        fail(f"invalid plugin ID: {plugin_id!r}")
    slug = plugin_id.split("/", 1)[1]
    if inner.get("providerId", inner.get("name")) != slug:
        fail(f"plugin ID does not match inner manifest identity: {plugin_id}")
    if package.get("type") == "management":
        if inner.get("providerVersion") != package.get("version"):
            fail("management package version does not match providerVersion")
        if not any(
            path.startswith("versions/") and path.endswith(".jar") and "/plugin/" in path
            for path in entries
        ):
            fail("management package must include a versioned plugin JAR")
        if inner.get("ui") and not any(
            path.startswith("versions/") and "/ui/" in path and path.endswith("remoteEntry.js")
            for path in entries
        ):
            fail("management package declares a remote UI but has no remoteEntry.js")
        ui_range = package["compatibility"].get("managementUiApiRange")
        if inner.get("ui") and not ui_range:
            fail("management package declares a UI but has no UI API compatibility range")
    elif package.get("type") == "media":
        if package.get("runtime") not in ("api", "comfyui"):
            fail("media package runtime must be api or comfyui")
        if inner.get("kind", "comfyui") != package.get("runtime"):
            fail("media runtime does not match adapter.json kind")
        if inner.get("version") != package.get("version"):
            fail("media package version does not match adapter.json version")
        if not any(path.endswith(".jar") for path in entries):
            fail("media package must include an adapter JAR")
        required_paths: list[str] = []
        for field in ("icon", "dependenciesFile", "workflowFile"):
            value = inner.get(field)
            if isinstance(value, str) and value:
                required_paths.append(value)
        images = inner.get("displayImage", [])
        if isinstance(images, str):
            images = [images]
        if isinstance(images, list):
            required_paths.extend(item for item in images if isinstance(item, str))
        if package["runtime"] == "comfyui" and not inner.get("workflowFile"):
            required_paths.append("workflow.json")
        for value in required_paths:
            path = safe_archive_path(value)
            if path not in entries:
                fail(f"media adapter references a missing package resource: {path}")
    elif package.get("type") == "profile":
        if inner.get("version") != package.get("version"):
            fail("Profile package version does not match plugin.json version")
        template_dir = inner.get("templateDir", "templates")
        template_prefix = safe_archive_path(template_dir).rstrip("/") + "/"
        if not any(path.startswith(template_prefix) for path in entries):
            fail(f"Profile package has no template resources under {template_dir}")
    else:
        fail(f"unsupported plugin type: {package.get('type')!r}")


def package_metadata(source: PluginSource, project_root: pathlib.Path) -> tuple[dict[str, Any], dict[str, PackageEntry]]:
    entries: dict[str, PackageEntry] = {}
    if source.plugin_type == "management":
        source_manifest = read_json(source.manifest_path)
        source_slug = str(source_manifest.get("providerId", ""))
        if not source_slug:
            fail(f"management providerId missing in {source.manifest_path}")
        manifest_source = source.module / "target/plugin-package" / source_slug / "management-plugin.json"
        if not manifest_source.is_file():
            fail(f"management plugin is not built; package output not found: {manifest_source}")
        inner = read_json(manifest_source)
        slug = str(inner.get("providerId", ""))
        if not slug:
            fail(f"management providerId missing in {manifest_source}")
        add_tree(entries, manifest_source.parent)
        version_raw = inner.get("providerVersion")
        api_range = normalize_api_range(inner.get("apiCompatibilityRange"))
        ui_range = normalize_api_range(inner["ui"]["uiApiCompatibilityRange"]) if inner.get("ui") else None
        runtime = None
        inner_name = "management-plugin.json"
        api_namespace = "management-api"
    elif source.plugin_type == "media":
        manifest_source = source.manifest_path
        inner = read_json(manifest_source)
        slug = str(inner.get("name", ""))
        if not slug:
            fail(f"media adapter name missing in {manifest_source}")
        runtime = str(inner.get("kind", "comfyui"))
        if runtime not in ("api", "comfyui"):
            fail(f"unsupported media runtime {runtime!r} in {manifest_source}")
        version_raw = inner.get("version")
        api_range = f"^{plugin_api_version(project_root)}"
        ui_range = None
        inner_name = "adapter.json"
        api_namespace = "media-plugin-api"
        jar = source.module / "target" / f"{slug}.jar"
        if not jar.is_file():
            fail(f"media plugin is not built; JAR not found: {jar}")
        add_source_file(entries, f"{slug}.jar", jar)
        source_root = source.module / "src/main/java"
        if not source_root.is_dir():
            fail(f"media plugin source resources not found: {source_root}")
        for path in sorted(source_root.rglob("*")):
            if path.is_symlink():
                fail(f"media plugin source contains a symlink: {path}")
            if not path.is_file() or path.suffix == ".java" or path.name.endswith(".bak") or path.name == "adapter.json":
                continue
            add_source_file(entries, path.name, path)
        # The existing loader and package format expect adapter.json at the package root.
        if "adapter.json" in entries:
            fail("media package has multiple root-flattened adapter.json files")
        if "adapter.json" not in entries:
            add_source_file(entries, "adapter.json", manifest_source)
    else:
        manifest_source = source.manifest_path
        inner = read_json(manifest_source)
        slug = str(inner.get("name", ""))
        if not slug:
            fail(f"Profile plugin name missing in {manifest_source}")
        version_raw = inner.get("version")
        runtime = None
        api_range = DEFAULT_PROFILE_API_RANGE
        ui_range = None
        inner_name = "plugin.json"
        api_namespace = "profile-manifest-schema"
        add_tree(entries, manifest_source.parent)

    version = normalize_version(version_raw)
    plugin_id = f"{PUBLISHER}/{slug}"
    if not PLUGIN_ID.fullmatch(plugin_id):
        fail(f"plugin name is not valid in a market ID: {slug!r}")
    if source.plugin_type in ("media", "profile"):
        inner["version"] = version
    if source.plugin_type == "media":
        entries[inner_name] = (json.dumps(inner, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    elif source.plugin_type == "profile":
        entries[inner_name] = (json.dumps(inner, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    elif inner_name not in entries:
        fail("management package output omitted its root manifest")

    revision = root_revision(project_root)
    major = revision.split(".", 1)[0]
    compatibility: dict[str, Any] = {
        "backendVersionRange": DEFAULT_BACKEND_RANGE if revision == "1.0.2" else f">={revision} <{int(major) + 1}.0.0",
        "pluginApi": {"namespace": api_namespace, "range": api_range},
    }
    if ui_range:
        compatibility["managementUiApiRange"] = ui_range
    package = {
        "schemaVersion": 1,
        "id": plugin_id,
        "publisher": PUBLISHER,
        "version": version,
        "type": source.plugin_type,
        "innerManifest": inner_name,
        "compatibility": compatibility,
    }
    if runtime is not None:
        package["runtime"] = runtime
    entries["dramatrix-plugin.json"] = (json.dumps(package, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    validate_inner_package(entries, package)
    return package, entries


def write_deterministic_zip(entries: dict[str, PackageEntry], destination: pathlib.Path) -> None:
    if len(entries) > MAX_ENTRIES:
        fail("plugin package has too many files")
    expanded_size = 0
    for name, data in entries.items():
        safe_archive_path(name)
        size = data.stat().st_size if isinstance(data, pathlib.Path) else len(data)
        if size > MAX_ENTRY_BYTES:
            fail(f"package file exceeds the per-file limit: {name}")
        expanded_size += size
        if expanded_size > MAX_EXPANDED_BYTES:
            fail("plugin package exceeds the expanded size limit")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            archive.comment = b""
            for name in sorted(entries):
                data = entries[name]
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.create_system = 3
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                with archive.open(info, "w", force_zip64=True) as output:
                    if isinstance(data, pathlib.Path):
                        with data.open("rb") as source:
                            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                                output.write(chunk)
                    else:
                        output.write(data)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
    if temporary.stat().st_size > MAX_ARCHIVE_BYTES:
        temporary.unlink(missing_ok=True)
        fail("plugin archive exceeds the compressed size limit")
    temporary.replace(destination)


def archive_entries(path: pathlib.Path) -> dict[str, bytes | None]:
    if not path.is_file():
        fail(f"plugin archive not found: {path}")
    if path.stat().st_size > MAX_ARCHIVE_BYTES:
        fail("plugin archive exceeds the compressed size limit")
    entries: dict[str, bytes | None] = {}
    folded_paths: set[str] = set()
    all_names: set[str] = set()
    expanded_size = 0
    try:
        with zipfile.ZipFile(path, "r") as archive:
            infos = archive.infolist()
            if len(infos) > MAX_ENTRIES:
                fail("plugin archive has too many entries")
            for info in infos:
                raw_name = info.filename[:-1] if info.is_dir() else info.filename
                if not raw_name or raw_name.endswith("/"):
                    fail(f"invalid ZIP entry path: {info.filename!r}")
                name = safe_archive_path(raw_name)
                if name in all_names or name.casefold() in folded_paths:
                    fail(f"duplicate or case-colliding ZIP path: {name}")
                all_names.add(name)
                folded_paths.add(name.casefold())
                if info.is_dir():
                    continue
                mode = info.external_attr >> 16
                file_type = stat.S_IFMT(mode)
                if file_type not in (0, stat.S_IFREG):
                    fail(f"ZIP contains a symbolic link or special file: {name}")
                if info.file_size > MAX_ENTRY_BYTES:
                    fail(f"ZIP entry exceeds the per-file limit: {name}")
                expanded_size += info.file_size
                if expanded_size > MAX_EXPANDED_BYTES:
                    fail("plugin archive exceeds the expanded size limit")
                ratio = info.file_size / max(info.compress_size, 1)
                if ratio > MAX_EXPANSION_RATIO:
                    fail(f"ZIP entry expansion ratio exceeds the limit: {name}")
                entries[name] = None
            if "dramatrix-plugin.json" not in entries:
                fail("plugin archive is missing dramatrix-plugin.json")
            package = read_json_bytes(archive.read("dramatrix-plugin.json"), "dramatrix-plugin.json")
            inner_path = safe_archive_path(package.get("innerManifest", ""))
            metadata_names = {"dramatrix-plugin.json", inner_path}
            for name in metadata_names:
                info = archive.getinfo(name)
                if info.file_size > 4 * 1024**2:
                    fail(f"package metadata file is too large: {name}")
                entries[name] = archive.read(info)
            # Stream every payload file so CRC errors are detected without keeping the
            # expanded archive in memory. Declared size and ratio limits were checked above.
            for info in infos:
                if info.is_dir() or info.filename in metadata_names:
                    continue
                with archive.open(info, "r") as stream:
                    for _ in iter(lambda: stream.read(64 * 1024), b""):
                        pass
    except zipfile.BadZipFile as exc:
        fail(f"invalid ZIP archive: {exc}")
    return entries


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact_payload(package: dict[str, Any], path: pathlib.Path) -> dict[str, Any]:
    compatibility = package["compatibility"]
    api = compatibility["pluginApi"]
    payload: dict[str, Any] = {
        "schemaVersion": 1,
        "publisher": package["publisher"],
        "pluginId": package["id"],
        "version": package["version"],
        "type": package["type"],
        "backendVersionRange": compatibility["backendVersionRange"],
        "pluginApiNamespace": api["namespace"],
        "pluginApiRange": api["range"],
        "archiveSize": path.stat().st_size,
        "archiveSha256": sha256_file(path),
    }
    if "runtime" in package:
        payload["runtime"] = package["runtime"]
    if "managementUiApiRange" in compatibility:
        payload["managementUiApiRange"] = compatibility["managementUiApiRange"]
    return payload


def openssl_sign(message: bytes, private_key_path: pathlib.Path) -> bytes:
    try:
        with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-sign-") as temp_dir:
            message_path = pathlib.Path(temp_dir) / "message.bin"
            signature_path = pathlib.Path(temp_dir) / "signature.bin"
            message_path.write_bytes(message)
            subprocess.run(
                ["openssl", "pkeyutl", "-sign", "-rawin", "-inkey", str(private_key_path),
                 "-in", str(message_path), "-out", str(signature_path)],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                check=True,
            )
            return signature_path.read_bytes()
    except FileNotFoundError:
        fail("OpenSSL is required to sign plugin artifacts")
    except subprocess.CalledProcessError as exc:
        fail(f"OpenSSL failed to sign the artifact: {exc.stderr.decode(errors='replace').strip()}")


def signer_key(args: argparse.Namespace) -> tuple[pathlib.Path | None, tempfile.TemporaryDirectory[str] | None]:
    if args.private_key:
        path = pathlib.Path(args.private_key).expanduser().resolve()
        if not path.is_file():
            fail(f"private key file not found: {path}")
        return path, None
    pem = os.environ.get("DRAMATRIX_PLUGIN_SIGNING_KEY_PEM")
    if pem:
        directory = tempfile.TemporaryDirectory(prefix="dramatrix-plugin-signing-")
        path = pathlib.Path(directory.name) / "ed25519.pem"
        path.write_text(pem, encoding="utf-8")
        path.chmod(0o600)
        return path, directory
    return None, None


def write_sidecar(package: dict[str, Any], archive_path: pathlib.Path, key_path: pathlib.Path, key_id: str) -> pathlib.Path:
    payload = artifact_payload(package, archive_path)
    message = b"Dramatrix-Plugin-Artifact-V1\n" + jcs_bytes(payload)
    signature = openssl_sign(message, key_path)
    envelope = {
        "payload": payload,
        "signature": {
            "algorithm": "Ed25519",
            "keyId": key_id,
            "value": base64.urlsafe_b64encode(signature).decode("ascii").rstrip("="),
        },
    }
    path = archive_path.with_suffix(archive_path.suffix + ".sig.json")
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(envelope, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.chmod(0o644)
    temporary.replace(path)
    return path


def verify_signature(archive_path: pathlib.Path, public_key_path: pathlib.Path) -> dict[str, Any]:
    sidecar_path = archive_path.with_suffix(archive_path.suffix + ".sig.json")
    envelope = read_json(sidecar_path)
    payload = envelope.get("payload")
    signature = envelope.get("signature")
    if not isinstance(payload, dict) or not isinstance(signature, dict):
        fail("artifact signature sidecar must contain payload and signature objects")
    entries = archive_entries(archive_path)
    package = read_json_bytes(entries.get("dramatrix-plugin.json"), "dramatrix-plugin.json")
    validate_inner_package(entries, package)
    expected = artifact_payload(package, archive_path)
    if payload != expected:
        fail("artifact signature payload does not match archive bytes or package metadata")
    if signature.get("algorithm") != "Ed25519" or not signature.get("keyId"):
        fail("unsupported or incomplete artifact signature metadata")
    try:
        signature_bytes = base64.urlsafe_b64decode(
            str(signature.get("value", "")) + "=" * (-len(str(signature.get("value", ""))) % 4)
        )
    except Exception as exc:
        fail(f"invalid base64url artifact signature: {exc}")
    message = b"Dramatrix-Plugin-Artifact-V1\n" + jcs_bytes(payload)
    # pkeyutl reads the signature file separately; use temporary files to avoid shell interpolation.
    with tempfile.TemporaryDirectory(prefix="dramatrix-plugin-verify-") as temp_dir:
        message_path = pathlib.Path(temp_dir) / "message.bin"
        signature_path = pathlib.Path(temp_dir) / "signature.bin"
        message_path.write_bytes(message)
        signature_path.write_bytes(signature_bytes)
        result = subprocess.run(
            ["openssl", "pkeyutl", "-verify", "-pubin", "-inkey", str(public_key_path),
             "-rawin", "-in", str(message_path), "-sigfile", str(signature_path)],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    if result.returncode != 0:
        fail("Ed25519 artifact signature verification failed")
    return package


def read_json_bytes(value: bytes | None, name: str) -> dict[str, Any]:
    if value is None:
        fail(f"plugin archive is missing {name}")
    try:
        parsed = strict_json_loads(value.decode("utf-8"))
    except Exception as exc:
        fail(f"invalid JSON in {name}: {exc}")
    if not isinstance(parsed, dict):
        fail(f"expected a JSON object in {name}")
    return parsed


def package_one(source: PluginSource, project_root: pathlib.Path, out_dir: pathlib.Path,
                key_path: pathlib.Path | None, key_id: str | None) -> tuple[pathlib.Path, pathlib.Path | None]:
    package, entries = package_metadata(source, project_root)
    slug = package["id"].split("/", 1)[1]
    archive_path = out_dir / f"{slug}-{package['version']}.zip"
    write_deterministic_zip(entries, archive_path)
    archive_metadata = archive_entries(archive_path)
    packaged_manifest = read_json_bytes(archive_metadata.get("dramatrix-plugin.json"), "dramatrix-plugin.json")
    validate_inner_package(archive_metadata, packaged_manifest)
    signature_path = None
    if key_path:
        if not key_id:
            fail("--key-id is required when signing plugin artifacts")
        signature_path = write_sidecar(package, archive_path, key_path, key_id)
    return archive_path, signature_path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    package = sub.add_parser("package", help="package one plugin module")
    package.add_argument("--project-root", default=".")
    package.add_argument("--module", required=True)
    package.add_argument("--type", choices=("management", "media", "profile"))
    package.add_argument("--output-dir", required=True)
    package.add_argument("--private-key")
    package.add_argument("--key-id")
    package.add_argument("--unsigned", action="store_true", help="build an unsigned local-only archive")
    package_all = sub.add_parser("package-all", help="package every built plugin module")
    package_all.add_argument("--project-root", default=".")
    package_all.add_argument("--type", choices=("management", "media", "profile"))
    package_all.add_argument("--output-dir", required=True)
    package_all.add_argument("--private-key")
    package_all.add_argument("--key-id")
    package_all.add_argument("--unsigned", action="store_true", help="build unsigned local-only archives")
    verify = sub.add_parser("verify", help="validate an archive and its Ed25519 signature")
    verify.add_argument("--archive", required=True)
    verify.add_argument("--public-key", required=True)
    return parser


def run(args: argparse.Namespace) -> None:
    if args.command == "verify":
        package = verify_signature(pathlib.Path(args.archive), pathlib.Path(args.public_key))
        print(f"verified {package['id']} {package['version']} ({package['type']})")
        return

    project_root = pathlib.Path(args.project_root).resolve()
    output_dir = pathlib.Path(args.output_dir).resolve()
    key_path, temp_key = signer_key(args)
    if args.unsigned and (key_path or args.key_id):
        if temp_key:
            temp_key.cleanup()
        fail("--unsigned cannot be combined with signing key options")
    if not args.unsigned and not key_path:
        fail("signing key required; set DRAMATRIX_PLUGIN_SIGNING_KEY_PEM or pass --private-key (or use --unsigned for local review)")
    try:
        if args.command == "package":
            module_path = pathlib.Path(args.module)
            if not module_path.is_absolute():
                module_path = (project_root / module_path).resolve()
            source = None
            for candidate in discover_plugins(project_root):
                if candidate.module.resolve() == module_path:
                    source = candidate
                    break
            if source is None:
                fail(f"no supported plugin manifest found under module: {module_path}")
            if args.type and source.plugin_type != args.type:
                fail(f"module is {source.plugin_type}, not requested type {args.type}")
            archive_path, signature_path = package_one(source, project_root, output_dir, key_path, args.key_id)
            print(f"packaged {archive_path}")
            if signature_path:
                print(f"signed {signature_path}")
            return

        sources = [
            source for source in discover_plugins(project_root)
            if args.type is None or source.plugin_type == args.type
        ]
        if not sources:
            fail("no plugin source manifests found")
        output_dir.mkdir(parents=True, exist_ok=True)
        ids: set[str] = set()
        for source in sources:
            package, _ = package_metadata(source, project_root)
            if package["id"] in ids:
                fail(f"duplicate plugin ID across package sources: {package['id']}")
            ids.add(package["id"])
        for source in sources:
            archive_path, signature_path = package_one(source, project_root, output_dir, key_path, args.key_id)
            print(f"packaged {source.plugin_type}: {archive_path.name}")
            if signature_path:
                print(f"signed: {signature_path.name}")
        print(f"plugin packages ready: {len(sources)}")
    finally:
        if temp_key:
            temp_key.cleanup()


def main() -> int:
    args = build_parser().parse_args()
    try:
        run(args)
    except PackageError as exc:
        print(f"plugin-package: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
