import pathlib
import tempfile
import unittest
import zipfile

import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
import plugin_package


class PluginPackageSafetyTests(unittest.TestCase):
    def test_backend_and_plugin_api_versions_are_independent(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            (root / "pom.xml").write_text(
                "<project><properties><revision>1.0.2</revision>"
                "<dramatrix.backend.version>1.2.3</dramatrix.backend.version>"
                "<plugin.api.version>4.5.6</plugin.api.version>"
                "</properties></project>"
            )
            self.assertEqual("1.2.3", plugin_package.root_revision(root))
            self.assertEqual("4.5.6", plugin_package.plugin_api_version(root))

    def test_safe_archive_path_accepts_normal_relative_path(self):
        self.assertEqual("versions/1.0.0/plugin/example.jar", plugin_package.safe_archive_path(
            "versions/1.0.0/plugin/example.jar"
        ))

    def test_safe_archive_path_rejects_traversal_and_windows_paths(self):
        for value in ("../escape", "a/../../escape", "/absolute", "C:\\escape", "a\\b", "a//b"):
            with self.subTest(value=value), self.assertRaises(plugin_package.PackageError):
                plugin_package.safe_archive_path(value)

    def test_jcs_sorting_is_canonical_and_float_free(self):
        self.assertEqual(b'{"a":1,"b":2}', plugin_package.jcs_bytes({"b": 2, "a": 1}))
        with self.assertRaises(plugin_package.PackageError):
            plugin_package.jcs_bytes({"size": 1.5})
        with self.assertRaises(plugin_package.PackageError):
            plugin_package.jcs_bytes({"bad": "\ud800"})

    def test_strict_json_rejects_duplicate_keys_and_invalid_semver(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = pathlib.Path(temp_dir) / "duplicate.json"
            path.write_text('{"schemaVersion":1,"schemaVersion":2}')
            with self.assertRaises(plugin_package.PackageError):
                plugin_package.read_json(path)
        self.assertEqual("1.0.0-rc.1", plugin_package.normalize_version("1.0.0-rc.1"))
        with self.assertRaises(plugin_package.PackageError):
            plugin_package.normalize_version("1.0.0-01")

    def test_archive_reader_rejects_traversal_and_case_collisions(self):
        for names in (("../escape",), ("Folder/a.txt", "folder/A.txt")):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as temp_dir:
                archive_path = pathlib.Path(temp_dir) / "unsafe.zip"
                with zipfile.ZipFile(archive_path, "w") as archive:
                    for name in names:
                        archive.writestr(name, "payload")
                with self.assertRaises(plugin_package.PackageError):
                    plugin_package.archive_entries(archive_path)

    def test_archive_reader_rejects_symlink_entries(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            archive_path = pathlib.Path(temp_dir) / "symlink.zip"
            info = zipfile.ZipInfo("link")
            info.create_system = 3
            info.external_attr = (0o120777 << 16)
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr(info, "target")
            with self.assertRaises(plugin_package.PackageError):
                plugin_package.archive_entries(archive_path)

    def test_deterministic_zip_output(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            root = pathlib.Path(temp_dir)
            first, second = root / "first.zip", root / "second.zip"
            entries = {"b.txt": b"second", "a.txt": b"first"}
            plugin_package.write_deterministic_zip(entries, first)
            plugin_package.write_deterministic_zip(entries, second)
            self.assertEqual(first.read_bytes(), second.read_bytes())


if __name__ == "__main__":
    unittest.main()
