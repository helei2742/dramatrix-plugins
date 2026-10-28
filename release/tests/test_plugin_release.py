import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import plugin_package
import plugin_release


class PluginReleaseIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="dramatrix-plugin-release-case-")
        self.root = pathlib.Path(self.temp.name)
        self.private_key = self.root / "signing-key.pem"
        self.public_key = self.root / "signing-public.pem"
        subprocess.run(["openssl", "genpkey", "-algorithm", "ED25519", "-out", str(self.private_key)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(["openssl", "pkey", "-in", str(self.private_key), "-pubout", "-out", str(self.public_key)],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        self.packages = self.root / "packages"
        self.packages.mkdir()
        self.archive = self.packages / "test-media-1.0.0.zip"
        manifest = {
            "schemaVersion": 1, "id": "dramatrix/test-media", "publisher": "dramatrix",
            "version": "1.0.0", "type": "media", "runtime": "api",
            "innerManifest": "adapter.json",
            "compatibility": {
                "backendVersionRange": ">=1.0.2 <2.0.0",
                "pluginApi": {"namespace": "media-plugin-api", "range": "^1.0.0"},
            },
        }
        adapter = {"name": "test-media", "kind": "api", "version": "1.0.0", "displayName": "Test Media"}
        entries = {
            "dramatrix-plugin.json": json.dumps(manifest).encode(),
            "adapter.json": json.dumps(adapter).encode(),
            "test-media.jar": b"test plugin bytes",
        }
        plugin_package.validate_inner_package(entries, manifest)
        plugin_package.write_deterministic_zip(entries, self.archive)
        plugin_package.write_sidecar(manifest, self.archive, self.private_key, "test-key")
        self.remote_store = {}

    def tearDown(self):
        self.temp.cleanup()

    def create_plan(self, output, generation=1):
        destinations = self.root / f"destinations-{generation}.json"
        destinations.write_text(json.dumps([
            {"id": "release-one", "downloadSourceId": "source-one", "provider": "github-release",
             "required": True, "repository": "test-org/test-release"},
        ]))
        args = argparse.Namespace(
            packages_dir=str(self.packages), destinations=str(destinations), catalog_destination="release-one",
            channel="stable", generation=generation, key_id="test-key", private_key=str(self.private_key),
            previous_catalog=None, previous_catalog_public_key=None,
            output=str(output),
        )
        plugin_release.create_plan(args)
        return destinations

    def fake_github_providers(self, fail_destination=None):
        def upload(destination, plugins, plan):
            if destination["id"] == fail_destination:
                raise plugin_release.ReleaseError("injected GitHub destination failure")
            for plugin in plugins:
                artifact = plugin["artifact"]
                for path_key, url_key in (("path", "sourceUrls"),
                                          ("signaturePath", "signatureSourceUrls")):
                    self.remote_store[artifact[url_key][destination["id"]]] = pathlib.Path(artifact[path_key]).read_bytes()

        def verify(destination, plugins, plan):
            if destination["id"] == fail_destination:
                raise plugin_release.ReleaseError("injected GitHub readback failure")
            for plugin in plugins:
                artifact = plugin["artifact"]
                for path_key, url_key in (("path", "sourceUrls"),
                                          ("signaturePath", "signatureSourceUrls")):
                    url = artifact[url_key][destination["id"]]
                    if self.remote_store.get(url) != pathlib.Path(artifact[path_key]).read_bytes():
                        raise plugin_release.ReleaseError(f"mock GitHub readback mismatch: {url}")

        return upload, verify

    def fake_github_catalog(self):
        def publish(destination, plan, catalog):
            data = pathlib.Path(catalog["path"]).read_bytes()
            self.remote_store[catalog["immutableUrl"]] = data

        def verify(destination, plan, catalog):
            if self.remote_store.get(catalog["immutableUrl"]) != pathlib.Path(catalog["path"]).read_bytes():
                raise plugin_release.ReleaseError("mock GitHub immutable catalog readback mismatch")

        def activate(destination, channel, generation, catalog_path, expected_size, expected_sha, work_dir):
            _, pointer_url = plugin_release.catalog_paths(destination, channel, generation)
            data = catalog_path.read_bytes()
            if len(data) != expected_size or hashlib.sha256(data).hexdigest() != expected_sha:
                raise plugin_release.ReleaseError("mock GitHub catalog activation input mismatch")
            self.remote_store[pointer_url] = data

        return publish, verify, activate

    def memory_transport(self, fail_destination=None, corrupt_readback_url=None):
        corrupt_next_readback = {"armed": False, "used": False}
        def request(destination, url, method, input_path=None, output_path=None, allow_404=False):
            if method == "GET":
                if url not in self.remote_store:
                    if allow_404:
                        return None
                    raise plugin_release.ReleaseError(f"mock HTTP 404: {url}")
                data = self.remote_store[url]
                if url == corrupt_readback_url and corrupt_next_readback["armed"]:
                    corrupt_next_readback["armed"] = False
                    return 200, 3, hashlib.sha256(b"bad").hexdigest()
                if output_path:
                    pathlib.Path(output_path).write_bytes(data)
                return 200, len(data), hashlib.sha256(data).hexdigest()
            if method == "PUT":
                if destination["id"] == fail_destination:
                    raise plugin_release.ReleaseError(f"injected destination failure: {destination['id']}")
                data = pathlib.Path(input_path).read_bytes()
                self.remote_store[url] = data
                if url == corrupt_readback_url and not corrupt_next_readback["used"]:
                    corrupt_next_readback["armed"] = True
                    corrupt_next_readback["used"] = True
                return 201, len(data), hashlib.sha256(data).hexdigest()
            if method == "DELETE":
                self.remote_store.pop(url, None)
                return 204, 0, hashlib.sha256(b"").hexdigest()
            raise AssertionError(method)
        return request

    def test_github_only_target_reads_back_and_activates_signed_catalog(self):
        plan_path = self.root / "release-plan.json"
        self.create_plan(plan_path)
        plugin_release.command_validate(plan_path)
        upload, verify = self.fake_github_providers()
        publish_catalog, verify_catalog_remote, activate = self.fake_github_catalog()
        with patch.object(plugin_release, "provider_upload_artifacts", upload), \
             patch.object(plugin_release, "provider_verify_artifacts", verify), \
             patch.object(plugin_release, "publish_catalog", publish_catalog), \
             patch.object(plugin_release, "verify_catalog_remote", verify_catalog_remote), \
             patch.object(plugin_release, "github_release", lambda destination, tag: None), \
             patch.object(plugin_release, "github_activate", activate):
            plugin_release.command_run(plan_path, self.private_key)
            plugin_release.command_validate(plan_path, require_catalog=True)
            plugin_release.command_activate(plan_path)
        plan = plugin_release.load_json(plan_path)
        record = plugin_release.load_json(pathlib.Path(plan["recordPath"]))

        artifact = plan["plugins"][0]["artifact"]
        self.assertEqual(self.archive.read_bytes(), self.remote_store[artifact["sourceUrls"]["release-one"]])
        active_catalog_url = plan["catalog"]["pointerUrl"]
        active_catalog = self.root / "active-catalog.json"
        active_catalog.write_bytes(self.remote_store[active_catalog_url])
        self.assertEqual(active_catalog.read_bytes(), pathlib.Path(plan["catalog"]["path"]).read_bytes())
        payload = plugin_release.verify_catalog(active_catalog, self.public_key)
        sources = payload["plugins"][0]["versions"][0]["artifact"]["sources"]
        self.assertEqual({"source-one"}, {item["sourceId"] for item in sources})
        self.assertTrue(all(set(item) == {"sourceId", "url"} for item in sources))
        self.assertEqual("activated", record["catalog"]["status"])
        destinations = record["plugins"][0]["destinations"]
        self.assertEqual({"release-one"}, set(destinations))
        self.assertTrue(all(item["readback"] == "verified" for item in destinations.values()))
        with self.assertRaises(plugin_release.ReleaseError):
            self.create_plan(plan_path, generation=1)

    def test_plan_rejects_static_or_multiple_destinations_in_v1(self):
        plan_path = self.root / "github-only-plan.json"
        self.create_plan(plan_path)
        plan = plugin_release.load_json(plan_path)
        github = plan["destinations"][0]
        static = {
            "id": "release-two", "downloadSourceId": "source-two", "provider": "https-static",
            "required": True, "baseUrl": "https://mirror.example/releases",
        }
        for destinations in ([static], [github, {**static, "required": False}]):
            with self.subTest(destinations=destinations):
                plan["destinations"] = destinations
                plan_path.write_text(json.dumps(plan))
                with self.assertRaisesRegex(plugin_release.ReleaseError, "exactly one required GitHub"):
                    plugin_release.validate_plan(plan_path)

    def test_github_release_tag_uses_release_repository_default_branch(self):
        destination = {"repository": "test-org/test-release"}
        target_sha = "a" * 40
        with patch.object(plugin_release, "github_default_branch_commit",
                          return_value=("main", target_sha)), patch.object(plugin_release, "gh") as gh:
            self.assertEqual(("main", target_sha), plugin_release.github_create_tag(destination, "plugin-test-v1.0.0"))
        gh.assert_called_once_with(
            "api", "--method", "POST", "repos/test-org/test-release/git/refs",
            "-f", "ref=refs/tags/plugin-test-v1.0.0", "-f", f"sha={target_sha}",
        )

    def test_github_draft_release_creation_works_with_older_gh_cli(self):
        destination = {"id": "release-one", "repository": "test-org/test-release"}
        draft = {"id": 1, "draft": True, "assets": []}
        with patch.object(plugin_release, "github_release", side_effect=[None, draft, draft]), \
             patch.object(plugin_release, "github_create_tag"), \
             patch.object(plugin_release, "gh", side_effect=[
                 plugin_release.ReleaseError("HTTP 404 Not Found"), None
             ]) as gh:
            plugin_release.github_publish_release(
                destination, "plugin-test-v1.0.0", "Dramatrix plugin test 1.0.0", [], publish=False
            )
        release_create_args = gh.call_args.args
        self.assertEqual(
            ("release", "create", "plugin-test-v1.0.0", "--repo", "test-org/test-release",
             "--title", "Dramatrix plugin test 1.0.0", "--notes-file"),
            release_create_args[:8],
        )
        self.assertEqual("--draft", release_create_args[-1])
        self.assertNotIn("--verify-tag", release_create_args)

    def test_github_release_lookup_falls_back_for_drafts(self):
        destination = {"repository": "test-org/test-release"}
        draft = {"id": 123, "tag_name": "plugin-test-v1.0.0", "draft": True, "assets": []}
        with patch.object(plugin_release, "gh", side_effect=[
            plugin_release.ReleaseError("GitHub command failed: HTTP 404 Not Found"),
            json.dumps(draft),
        ]) as gh:
            self.assertEqual(draft, plugin_release.github_release(destination, "plugin-test-v1.0.0"))
        gh.assert_any_call(
            "api", "--paginate", "repos/test-org/test-release/releases", "--jq",
            '.[] | select(.tag_name == "plugin-test-v1.0.0")',
        )

    def test_github_release_upload_batches_asset_readback(self):
        destination = {"id": "release-one", "repository": "test-org/test-release"}
        draft = {"id": 123, "tag_name": "plugin-test-v1.0.0", "draft": True, "assets": []}
        assets = []
        for name in ("plugin.zip", "plugin.zip.sig.json"):
            asset = self.root / name
            asset.write_bytes(name.encode())
            assets.append(asset)
        with patch.object(plugin_release, "github_release", side_effect=[draft, draft]) as get_release, \
             patch.object(plugin_release, "github_asset_matches", return_value=True) as asset_matches, \
             patch.object(plugin_release, "gh") as gh:
            plugin_release.github_publish_release(
                destination, "plugin-test-v1.0.0", "Dramatrix plugin test 1.0.0", assets, publish=False
            )
        self.assertEqual(2, get_release.call_count)
        self.assertEqual(2, asset_matches.call_count)
        self.assertEqual(2, gh.call_count)
        self.assertTrue(all(call.args[0:2] == ("release", "upload") for call in gh.call_args_list))

    def test_github_asset_upload_rechecks_after_timeout_before_retry(self):
        destination = {"id": "release-one", "repository": "test-org/test-release"}
        draft = {"id": 123, "tag_name": "plugin-test-v1.0.0", "draft": True, "assets": []}
        asset = self.root / "plugin.zip"
        asset.write_bytes(b"plugin bytes")
        with patch.object(plugin_release, "github_release", return_value=draft) as get_release, \
             patch.object(plugin_release, "gh", side_effect=[
                 plugin_release.ReleaseError("HTTP 408: upload body timed out"), None
             ]) as gh, \
             patch.object(plugin_release.time, "sleep") as sleep:
            plugin_release.github_upload_asset(destination, "plugin-test-v1.0.0", asset)
        self.assertEqual(2, gh.call_count)
        self.assertEqual(1, get_release.call_count)
        sleep.assert_called_once_with(1)

    def test_github_catalog_stage_renames_asset_uploaded_by_old_gh(self):
        destination = {"repository": "test-org/test-release"}
        catalog = self.root / "catalog-stable-1.json"
        data = b"signed catalog bytes"
        catalog.write_bytes(data)
        digest = hashlib.sha256(data).hexdigest()
        uploaded = {"id": 456, "name": catalog.name, "size": len(data), "digest": f"sha256:{digest}"}
        pending = {**uploaded, "name": "catalog-stable.pending-1.json"}
        with patch.object(plugin_release, "github_release", return_value={"assets": [pending]}) as get_release, \
             patch.object(plugin_release, "gh") as gh:
            result = plugin_release.github_stage_catalog_asset(
                destination, "plugin-catalog-stable", {"assets": [uploaded]}, catalog,
                pending["name"], len(data), digest,
            )
        self.assertEqual(pending, result)
        gh.assert_called_once_with(
            "api", "--method", "PATCH", "repos/test-org/test-release/releases/assets/456",
            "-F", "name=catalog-stable.pending-1.json",
        )
        get_release.assert_called_once_with(destination, "plugin-catalog-stable")

    def test_github_get_retries_transient_connection_failures(self):
        failure = subprocess.CalledProcessError(1, ["gh", "api"], stderr="unexpected EOF")
        success = subprocess.CompletedProcess(["gh", "api"], 0, stdout='{"ok":true}', stderr="")
        with patch.object(plugin_release.shutil, "which", return_value="/usr/bin/gh"), \
             patch.object(plugin_release.subprocess, "run", side_effect=[failure, success]) as run, \
             patch.object(plugin_release.time, "sleep") as sleep:
            self.assertEqual('{"ok":true}', plugin_release.gh("api", "repos/test-org/test-release"))
        self.assertEqual(2, run.call_count)
        sleep.assert_called_once_with(1)

    def test_github_public_download_retries_transient_tls_failures(self):
        failure = subprocess.CalledProcessError(
            35, ["curl"], stderr=b"LibreSSL SSL_ERROR_SYSCALL"
        )
        success = subprocess.CompletedProcess(["curl"], 0, stdout=b"", stderr=b"")
        target = self.root / "download.zip"
        with patch.object(plugin_release.shutil, "which", return_value="/usr/bin/curl"), \
             patch.object(plugin_release.subprocess, "run", side_effect=[failure, success]) as run, \
             patch.object(plugin_release.time, "sleep") as sleep:
            plugin_release.github_public_download(
                {"repository": "test-org/test-release"}, "plugin-test-v1.0.0", "plugin.zip", target
            )
        self.assertEqual(2, run.call_count)
        sleep.assert_called_once_with(1)

    def test_github_release_repository_without_default_branch_commit_is_rejected(self):
        destination = {"repository": "test-org/test-release"}
        with patch.object(plugin_release, "gh", side_effect=[
            json.dumps({"default_branch": "main"}),
            plugin_release.ReleaseError("GitHub command failed: HTTP 404 Not Found"),
        ]):
            with self.assertRaisesRegex(plugin_release.ReleaseError, "repository is empty"):
                plugin_release.github_default_branch_commit(destination)

    def test_required_target_failure_keeps_catalog_unactivated(self):
        plan_path = self.root / "failed-plan.json"
        self.create_plan(plan_path)
        upload, verify = self.fake_github_providers(fail_destination="release-one")
        with patch.object(plugin_release, "provider_upload_artifacts", upload), \
             patch.object(plugin_release, "provider_verify_artifacts", verify):
            with self.assertRaises(plugin_release.ReleaseError):
                plugin_release.command_publish(plan_path, self.private_key)
        plan = plugin_release.load_json(plan_path)
        self.assertIsNone(plan["catalog"])
        self.assertFalse(any("/catalogs/" in url for url in self.remote_store))
        record = plugin_release.load_json(pathlib.Path(plan["recordPath"]))
        self.assertEqual("failed", record["destinations"]["release-one"]["verify"])

    def test_same_immutable_object_name_rejects_different_digest(self):
        path = self.root / "new-bytes.zip"
        path.write_bytes(b"new bytes")
        url = "https://mirror.example/plugins/test-media-1.0.0.zip"
        self.remote_store[url] = b"old bytes"
        destination = {"id": "release-one", "provider": "https-static", "required": True,
                       "downloadSourceId": "source-one", "baseUrl": "https://mirror.example"}
        with patch.object(plugin_release, "static_request", self.memory_transport()):
            with self.assertRaises(plugin_release.ReleaseError):
                plugin_release.static_put_immutable(
                    destination, url, path, path.stat().st_size, plugin_release.sha256_file(path)
                )

    def test_pointer_activation_restores_previous_bytes_when_readback_fails(self):
        pointer_url = "https://mirror.example/catalogs/stable/current.json"
        old_bytes = b"old signed catalog bytes"
        self.remote_store[pointer_url] = old_bytes
        new_catalog = self.root / "catalog.json"
        new_catalog.write_bytes(b"new signed catalog bytes")
        destination = {"id": "release-one", "provider": "https-static", "required": True,
                       "downloadSourceId": "source-one", "baseUrl": "https://mirror.example"}
        with patch.object(plugin_release, "static_request",
                          self.memory_transport(corrupt_readback_url=pointer_url)):
            with self.assertRaises(plugin_release.ReleaseError):
                plugin_release.static_activate(
                    destination, pointer_url, new_catalog, new_catalog.stat().st_size,
                    plugin_release.sha256_file(new_catalog), self.root
                )
        self.assertEqual(old_bytes, self.remote_store[pointer_url])

    def test_catalog_groups_multiple_versions_for_one_plugin(self):
        manifest = {
            "schemaVersion": 1, "id": "dramatrix/test-media", "publisher": "dramatrix",
            "version": "1.1.0", "type": "media", "runtime": "api",
            "innerManifest": "adapter.json",
            "compatibility": {
                "backendVersionRange": ">=1.0.2 <2.0.0",
                "pluginApi": {"namespace": "media-plugin-api", "range": "^1.0.0"},
            },
        }
        entries = {
            "dramatrix-plugin.json": json.dumps(manifest).encode(),
            "adapter.json": json.dumps({"name": "test-media", "kind": "api", "version": "1.1.0"}).encode(),
            "test-media.jar": b"test plugin version 1.1.0",
        }
        archive = self.packages / "test-media-1.1.0.zip"
        plugin_package.write_deterministic_zip(entries, archive)
        plugin_package.write_sidecar(manifest, archive, self.private_key, "test-key")
        destination_configs = [
            {"id": "release-one", "downloadSourceId": "source-one", "provider": "https-static",
             "required": True, "baseUrl": "https://first.example/releases"},
            {"id": "release-two", "downloadSourceId": "source-two", "provider": "https-static",
             "required": True, "baseUrl": "https://second.example/releases"},
        ]
        destinations = [plugin_release.validate_destination(item, False) for item in destination_configs]
        plugins = plugin_release.read_package_plugins(
            self.packages, self.public_key, destinations, "test-key", {}
        )
        payload = plugin_release.build_catalog_payload(
            plugins, destinations, "stable", 1, {"release-one", "release-two"},
            datetime.datetime.now(datetime.timezone.utc),
        )
        self.assertEqual(1, len(payload["plugins"]))
        self.assertEqual({"1.0.0", "1.1.0"}, {item["version"] for item in payload["plugins"][0]["versions"]})


if __name__ == "__main__":
    unittest.main()
