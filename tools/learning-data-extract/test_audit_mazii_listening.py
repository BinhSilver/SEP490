import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audit_mazii_listening import audit_roots, parse_question_text


CATALOG_HEADERS = [
    "cap_do", "loai_de", "so_de", "id_de_nguon", "mondai", "loai_mondai",
    "cau_trong_mondai", "nhom_audio_trong_mondai", "thu_tu_nhom_trong_toan_phan_nghe",
    "id_cau_nguon", "so_cau_con_nguon", "nhan_cau_nguon", "audio_dung_chung_cac_cau",
    "file_audio", "trang_thai_audio", "file_script", "trang_thai_script",
    "file_cau_hoi_dap_an", "dap_an_so_1_based", "file_hinh", "source_audio_url",
    "source_url", "thu_muc_cau", "goi_zip", "file_script_nguon_truoc",
    "truong_script_uu_tien", "goi_zip_script",
]


class MaziiListeningAuditTest(unittest.TestCase):
    def test_resolves_one_catalog_across_split_roots_and_never_marks_it_import_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            catalog_root, media_root = base / "catalog", base / "media"
            catalog_root.mkdir()
            media_root.mkdir()
            row = dict.fromkeys(CATALOG_HEADERS, "")
            row.update({
                "cap_do": "N4", "loai_de": "JLPT", "so_de": "1", "id_de_nguon": "100",
                "mondai": "1", "loai_mondai": "nghe hiểu chủ đề", "cau_trong_mondai": "1",
                "id_cau_nguon": "9001", "so_cau_con_nguon": "1",
                "file_audio": "N4/JLPT/De_01/Cau_01/QID9001_AUDIO.mp3", "trang_thai_audio": "ok",
                "file_script": "N4/JLPT/De_01/Cau_01/QID9001_SCRIPT_NGUON.txt",
                "trang_thai_script": "source_text_available",
                "file_cau_hoi_dap_an": "N4/JLPT/De_01/Cau_01/QID9001_CAU_HOI_DAP_AN.txt",
                "dap_an_so_1_based": "2", "source_audio_url": "https://example.test/a.mp3",
                "source_url": "https://example.test/exam/100", "goi_zip": "N4_Goi_01.zip",
                "goi_zip_script": "N4_Huong_dan.zip",
            })
            with (catalog_root / "DANH_MUC_TOAN_BO.csv").open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=CATALOG_HEADERS)
                writer.writeheader()
                writer.writerow(row)
            (catalog_root / "QID9001_SCRIPT_NGUON.txt").write_text("lời thoại", encoding="utf-8")
            (media_root / "QID9001_AUDIO.mp3").write_bytes(b"ID3\x04\x00\x00audio")
            (media_root / "QID9001_CAU_HOI_DAP_AN.txt").write_text(
                "Nhãn câu nguồn: 何をしますか。\n\nLỰA CHỌN\n1. 行きます\n2. 帰ります\n\nĐÁP ÁN: 2\n",
                encoding="utf-8",
            )

            result = audit_roots([catalog_root, media_root])

            self.assertEqual(1, result["summary"]["catalog_rows"])
            self.assertEqual(1, result["summary"]["audio_files_resolved"])
            self.assertEqual(1, result["summary"]["script_files_resolved"])
            self.assertEqual(1, result["summary"]["question_files_resolved"])
            candidate = result["candidates"][0]
            self.assertEqual("帰ります", candidate["correct_answer"])
            self.assertFalse(candidate["ready_for_import"])
            self.assertFalse(candidate["license_verified"])
            self.assertEqual("NOT_REVIEWED", candidate["review_status"])

    def test_question_parser_keeps_audio_only_number_options_without_inventing_text(self):
        parsed = parse_question_text(
            "Nhãn câu nguồn:\nHướng dẫn nguồn: まず聞いてください。\n\n"
            "LỰA CHỌN\n1. 1\n2. 2\n3. 3\n\nĐÁP ÁN: 3\n"
            "Chỉ số correctAnswer nguyên gốc: 2\nNguồn: https://example.test/exam/1\n"
        )

        self.assertEqual("まず聞いてください。", parsed["prompt_ja"])
        self.assertEqual(["1", "2", "3"], parsed["options"])
        self.assertEqual("3", parsed["correct_answer"])
        self.assertTrue(parsed["audio_only_options"])

    def test_same_basename_with_different_bytes_is_reported_as_conflict(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            left, right = base / "left", base / "right"
            left.mkdir()
            right.mkdir()
            (left / "same.mp3").write_bytes(b"ID3left")
            (right / "same.mp3").write_bytes(b"ID3right")

            result = audit_roots([left, right], allow_missing_catalog=True)

            self.assertEqual(1, result["summary"]["generic_conflicting_basenames"])
            self.assertEqual(0, result["summary"]["conflicting_catalog_asset_basenames"])
            self.assertEqual("same.mp3", result["conflicts"][0]["basename"])
            self.assertEqual(2, len(result["conflicts"][0]["variants"]))

    def test_falls_back_to_source_audio_url_basename_when_standardized_copy_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = dict.fromkeys(CATALOG_HEADERS, "")
            row.update({
                "cap_do": "N3", "loai_de": "JLPT", "so_de": "1",
                "id_de_nguon": "101", "mondai": "2", "cau_trong_mondai": "1",
                "id_cau_nguon": "9002", "so_cau_con_nguon": "1",
                "file_audio": "N3/JLPT/QID9002_AUDIO.mp3",
                "source_audio_url": "https://cdn.example.test/audio/raw-audio-hash.mp3",
            })
            with (root / "DANH_MUC_TOAN_BO.csv").open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=CATALOG_HEADERS)
                writer.writeheader()
                writer.writerow(row)
            (root / "raw-audio-hash.mp3").write_bytes(b"ID3raw")

            result = audit_roots([root])

            candidate = result["candidates"][0]
            self.assertEqual(1, result["summary"]["audio_files_resolved"])
            self.assertEqual("SOURCE_AUDIO_URL", candidate["audio_resolution_source"])
            self.assertIn("STANDARDIZED_AUDIO_MISSING_RAW_SOURCE_USED", candidate["issues"])

    def test_resolves_hashed_raw_audio_through_listening_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = dict.fromkeys(CATALOG_HEADERS, "")
            row.update({
                "cap_do": "N2", "loai_de": "JLPT", "so_de": "1",
                "id_de_nguon": "102", "mondai": "3", "cau_trong_mondai": "1",
                "id_cau_nguon": "9003", "so_cau_con_nguon": "1",
                "file_audio": "N2/JLPT/QID9003_AUDIO.mp3",
                "source_audio_url": "https://cdn.example.test/audio/original.mp3",
            })
            with (root / "DANH_MUC_TOAN_BO.csv").open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=CATALOG_HEADERS)
                writer.writeheader()
                writer.writerow(row)
            with (root / "listening.csv").open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=[
                    "type", "level", "exam_id", "exam_title", "question_id", "kind",
                    "audio_url", "audio_file", "script_file", "source_url",
                ])
                writer.writeheader()
                writer.writerow({
                    "audio_url": row["source_audio_url"],
                    "audio_file": "audio/hashed-local-name.mp3",
                })
            (root / "hashed-local-name.mp3").write_bytes(b"ID3manifest")

            result = audit_roots([root])

            candidate = result["candidates"][0]
            self.assertEqual(1, result["summary"]["audio_files_resolved"])
            self.assertEqual("RAW_LISTENING_MANIFEST", candidate["audio_resolution_source"])
            self.assertIn("STANDARDIZED_AUDIO_MISSING_RAW_SOURCE_USED", candidate["issues"])

    def test_rejects_standardized_audio_that_disagrees_with_raw_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = dict.fromkeys(CATALOG_HEADERS, "")
            row.update({
                "cap_do": "N1", "loai_de": "JLPT", "so_de": "1",
                "id_de_nguon": "103", "mondai": "4", "cau_trong_mondai": "1",
                "id_cau_nguon": "9004", "so_cau_con_nguon": "1",
                "file_audio": "N1/JLPT/QID9004_AUDIO.mp3",
                "source_audio_url": "https://cdn.example.test/audio/source.mp3",
            })
            with (root / "DANH_MUC_TOAN_BO.csv").open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=CATALOG_HEADERS)
                writer.writeheader()
                writer.writerow(row)
            with (root / "listening.csv").open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=["audio_url", "audio_file"])
                writer.writeheader()
                writer.writerow({"audio_url": row["source_audio_url"], "audio_file": "audio/raw.mp3"})
            (root / "QID9004_AUDIO.mp3").write_bytes(b"ID3standardized")
            (root / "raw.mp3").write_bytes(b"ID3different")

            result = audit_roots([root])

            candidate = result["candidates"][0]
            self.assertEqual("", candidate["audio_path"])
            self.assertIn("CONFLICTING_STANDARDIZED_AND_RAW_AUDIO", candidate["issues"])

    def test_flags_catalog_row_with_no_audio_reference(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = dict.fromkeys(CATALOG_HEADERS, "")
            row.update({
                "cap_do": "N5", "loai_de": "JLPT", "so_de": "1",
                "id_de_nguon": "104", "mondai": "1", "cau_trong_mondai": "1",
                "id_cau_nguon": "9005", "so_cau_con_nguon": "1",
            })
            with (root / "DANH_MUC_TOAN_BO.csv").open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=CATALOG_HEADERS)
                writer.writeheader()
                writer.writerow(row)

            result = audit_roots([root])

            candidate = result["candidates"][0]
            self.assertEqual("", candidate["audio_path"])
            self.assertIn("MISSING_AUDIO_REFERENCE", candidate["issues"])

    def test_flags_source_url_without_resolvable_local_audio(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            row = dict.fromkeys(CATALOG_HEADERS, "")
            row.update({
                "cap_do": "N5", "loai_de": "JLPT", "so_de": "1",
                "id_de_nguon": "105", "mondai": "1", "cau_trong_mondai": "1",
                "id_cau_nguon": "9006", "so_cau_con_nguon": "1",
                "source_audio_url": "https://cdn.example.test/audio/not-downloaded.mp3",
            })
            with (root / "DANH_MUC_TOAN_BO.csv").open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=CATALOG_HEADERS)
                writer.writeheader()
                writer.writerow(row)

            result = audit_roots([root])

            candidate = result["candidates"][0]
            self.assertEqual("", candidate["audio_path"])
            self.assertIn("MISSING_LOCAL_FILE_AUDIO", candidate["issues"])
            self.assertNotIn("MISSING_AUDIO_REFERENCE", candidate["issues"])

    def test_machine_report_can_be_serialized_without_path_objects(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "audio.mp3").write_bytes(b"ID3audio")

            encoded = json.dumps(audit_roots([root], allow_missing_catalog=True), ensure_ascii=False)

            self.assertIn('"inventory_files": 1', encoded)


if __name__ == "__main__":
    unittest.main()
