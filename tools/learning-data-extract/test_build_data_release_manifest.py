import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from build_data_release_manifest import build_manifest, write_manifest


class DataReleaseManifestTest(unittest.TestCase):
    def test_manifest_is_sorted_and_hashes_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first = root / "b.xlsx"
            second = root / "a.xlsx"
            first.write_bytes(b"B")
            second.write_bytes(b"A")

            manifest = build_manifest([first, second], roles={second: "LEARNING_WORKBOOK"})

            self.assertEqual([item["path"] for item in manifest["files"]], [str(second.resolve()), str(first.resolve())])
            self.assertEqual(manifest["files"][0]["sha256"], hashlib.sha256(b"A").hexdigest())
            self.assertEqual(manifest["files"][0]["size"], 1)
            self.assertEqual(manifest["files"][0]["role"], "LEARNING_WORKBOOK")
            self.assertEqual(manifest["files"][1]["role"], "SOURCE")

    def test_write_manifest_refuses_to_overwrite_existing_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "manifest.json"
            output.write_text(json.dumps({"existing": True}), encoding="utf-8")

            with self.assertRaisesRegex(FileExistsError, "already exists"):
                write_manifest({"files": []}, output)


if __name__ == "__main__":
    unittest.main()
