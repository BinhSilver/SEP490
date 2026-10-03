"""Read-only audit and staging export for split Mazii listening handoffs.

This tool never edits source files or production workbooks.  Every staged row remains
NOT_REVIEWED/DRAFT/ready_for_import=false until provenance, license, answer and semantic
review are completed by a human owner.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlparse


CATALOG_NAME = "DANH_MUC_TOAN_BO.csv"
MP3_PREFIXES = (b"ID3",)
OWNER_RIGHTS_STATUS = "OWNER_CONFIRMED"
OWNER_RIGHTS_EVIDENCE_REF = "OWNER_CONFIRMATION_2026-10-03"


def classify_candidate_usage(exam_type: str) -> dict:
    """Keep source identity while selecting the only permitted downstream use."""
    source_exam_type = (exam_type or "UNKNOWN").strip().upper()
    if source_exam_type == "JLPT":
        return {
            "source_exam_type": source_exam_type,
            "target_usage": "JLPT_ASSESSMENT_CANDIDATE",
            "supplemental_jlpt_practice_allowed": False,
            "assessment_blueprint_policy_eligible": True,
            "assessment_blueprint_eligible": False,
            "mapping_status": "NEEDS_MAPPING",
            "supplement_reason": "",
        }
    return {
        "source_exam_type": source_exam_type,
        "target_usage": "PRACTICE_ONLY",
        "supplemental_jlpt_practice_allowed": source_exam_type == "JLCT",
        "assessment_blueprint_policy_eligible": False,
        "assessment_blueprint_eligible": False,
        "mapping_status": "NEEDS_MAPPING",
        "supplement_reason": "",
    }


def import_readiness_blockers(candidate: dict) -> list[str]:
    """Return every unmet fail-closed gate for a Listening candidate."""
    blockers = []
    if candidate.get("rights_status") != OWNER_RIGHTS_STATUS:
        blockers.append("RIGHTS_NOT_CONFIRMED")
    if not str(candidate.get("rights_evidence_ref", "")).strip():
        blockers.append("RIGHTS_EVIDENCE_MISSING")
    if not candidate.get("license_verified"):
        blockers.append("LICENSE_NOT_VERIFIED")
    if candidate.get("cleaning_status") != "CLEAN":
        blockers.append("CLEANING_REQUIRED")
    if candidate.get("mapping_status") != "APPROVED":
        blockers.append("MAPPING_REVIEW_REQUIRED")
    source_exam_type = candidate.get("source_exam_type")
    if source_exam_type not in {"JLPT", "JLCT"}:
        blockers.append("UNSUPPORTED_SOURCE_EXAM_TYPE")
    if (candidate.get("target_usage") == "JLPT_ASSESSMENT_CANDIDATE"
            and not candidate.get("assessment_blueprint_eligible")):
        blockers.append("ASSESSMENT_BLUEPRINT_MAPPING_REQUIRED")
    if not candidate.get("playback_verified"):
        blockers.append("PLAYBACK_REVIEW_REQUIRED")
    if not candidate.get("answer_key_verified"):
        blockers.append("ANSWER_KEY_REVIEW_REQUIRED")
    if not candidate.get("provenance_complete"):
        blockers.append("PROVENANCE_REVIEW_REQUIRED")
    if not candidate.get("semantic_reviewed"):
        blockers.append("SEMANTIC_REVIEW_REQUIRED")
    if candidate.get("review_status") != "APPROVED":
        blockers.append("APPROVAL_REQUIRED")
    for field, issue in (
        ("prompt_ja", "MISSING_PROMPT"),
        ("options", "MISSING_OPTIONS"),
        ("correct_answer", "MISSING_OR_INVALID_ANSWER"),
        ("audio_path", "MISSING_LOCAL_FILE_AUDIO"),
        ("script_path", "MISSING_LOCAL_FILE_SCRIPT"),
        ("question_source_path", "MISSING_LOCAL_FILE_QUESTION"),
        ("explanation_vi", "EXPLANATION_VI_MISSING"),
    ):
        if not candidate.get(field):
            blockers.append(issue)
    return sorted(set(blockers))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def valid_mp3_header(path: Path) -> bool:
    with path.open("rb") as stream:
        header = stream.read(3)
    return header.startswith(MP3_PREFIXES) or (
        len(header) >= 2 and header[0] == 0xFF and (header[1] & 0xE0) == 0xE0
    )


def parse_question_text(text: str) -> dict:
    # Horizontal whitespace only: ``\s`` would consume the newline after an empty
    # source label and incorrectly absorb the next metadata line as the label.
    label = re.search(r"^Nhãn câu nguồn:[^\S\r\n]*(.*)$", text, re.MULTILINE)
    instruction = re.search(r"^Hướng dẫn nguồn:[^\S\r\n]*(.*)$", text, re.MULTILINE)
    answer = re.search(r"^ĐÁP ÁN:\s*(\d+)\s*$", text, re.MULTILINE)
    source = re.search(r"^Nguồn:\s*(\S+)\s*$", text, re.MULTILINE)
    option_block = re.search(r"^LỰA CHỌN\s*$\n(.*?)(?:\n\s*\nĐÁP ÁN:|\nĐÁP ÁN:)", text, re.MULTILINE | re.DOTALL)
    options = []
    if option_block:
        options = [match.group(2).strip() for match in
                   re.finditer(r"^\s*(\d+)\.\s*(.*?)\s*$", option_block.group(1), re.MULTILINE)]
    answer_index = int(answer.group(1)) if answer else None
    correct = options[answer_index - 1] if answer_index and answer_index <= len(options) else None
    label_text = label.group(1).strip() if label else ""
    instruction_text = instruction.group(1).strip() if instruction else ""
    prompt = label_text or instruction_text
    return {
        "prompt_ja": prompt,
        "source_label": label_text,
        "source_instruction": instruction_text,
        "options": options,
        "correct_option_order": answer_index,
        "correct_answer": correct,
        "source_url_in_file": source.group(1) if source else "",
        "audio_only_options": bool(options) and all(value == str(index)
                                                       for index, value in enumerate(options, 1)),
    }


def _catalog_files(roots: list[Path]) -> list[Path]:
    return sorted(path for root in roots for path in root.rglob(CATALOG_NAME) if path.is_file())


def _select_catalog(roots: list[Path], allow_missing: bool) -> Path | None:
    catalogs = _catalog_files(roots)
    if not catalogs:
        if allow_missing:
            return None
        raise ValueError(f"Missing {CATALOG_NAME}")
    digests = {sha256_file(path) for path in catalogs}
    if len(digests) != 1:
        raise ValueError("Conflicting DANH_MUC_TOAN_BO.csv versions: " + ", ".join(map(str, catalogs)))
    return catalogs[0]


def _entry(path: Path, root: Path) -> dict:
    return {
        "path": str(path.resolve()),
        "root": str(root.resolve()),
        "relative_path": path.relative_to(root).as_posix(),
        "basename": path.name,
        "extension": path.suffix.lower(),
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def _resolve(index: dict[str, list[dict]], source_path: str) -> tuple[dict | None, list[dict]]:
    if not source_path:
        return None, []
    matches = index.get(Path(source_path).name.casefold(), [])
    if not matches:
        return None, []
    variants = {entry["sha256"] for entry in matches}
    if len(variants) != 1:
        return None, matches
    return sorted(matches, key=lambda entry: (len(entry["relative_path"]), entry["path"]))[0], matches


def _url_basename(url: str) -> str:
    if not url:
        return ""
    return Path(unquote(urlparse(url).path)).name


def _load_raw_audio_manifests(roots: list[Path]) -> tuple[dict[str, set[str]], list[str]]:
    by_url = defaultdict(set)
    manifest_paths = []
    for root in roots:
        for path in sorted(root.rglob("listening.csv")):
            if not path.is_file():
                continue
            with path.open(encoding="utf-8-sig", newline="") as stream:
                reader = csv.DictReader(stream)
                if not reader.fieldnames or not {"audio_url", "audio_file"}.issubset(reader.fieldnames):
                    continue
                manifest_paths.append(str(path.resolve()))
                for row in reader:
                    url = row.get("audio_url", "").strip()
                    audio_file = row.get("audio_file", "").strip()
                    if url and audio_file:
                        by_url[url].add(Path(audio_file).name)
    return by_url, manifest_paths


def _read_text(entry: dict | None) -> str:
    return Path(entry["path"]).read_text(encoding="utf-8-sig") if entry else ""


def _code(row: dict) -> str:
    kind = row.get("loai_de", "UNKNOWN").strip().upper()
    qid = row.get("id_cau_nguon", "UNKNOWN").strip()
    child = row.get("so_cau_con_nguon", "1").strip() or "1"
    return f"MAZII-LISTEN-{row.get('cap_do', 'NX').strip()}-{kind}-Q{qid}-S{child}"


def audit_roots(raw_roots, allow_missing_catalog: bool = False) -> dict:
    roots = [Path(root).resolve() for root in raw_roots]
    if not roots or any(not root.is_dir() for root in roots):
        raise ValueError("Every Mazii source root must be an existing directory")

    inventory = []
    by_basename = defaultdict(list)
    for root in roots:
        for path in sorted(item for item in root.rglob("*") if item.is_file()):
            entry = _entry(path, root)
            inventory.append(entry)
            by_basename[path.name.casefold()].append(entry)

    conflicts = []
    duplicate_copies = 0
    for basename, entries in sorted(by_basename.items()):
        groups = defaultdict(list)
        for entry in entries:
            groups[entry["sha256"]].append(entry["path"])
        duplicate_copies += sum(max(0, len(paths) - 1) for paths in groups.values())
        if len(groups) > 1:
            conflicts.append({
                "basename": entries[0]["basename"],
                "variants": [{"sha256": digest, "paths": paths} for digest, paths in sorted(groups.items())],
            })

    catalog_path = _select_catalog(roots, allow_missing_catalog)
    rows = []
    if catalog_path:
        with catalog_path.open(encoding="utf-8-sig", newline="") as stream:
            rows = list(csv.DictReader(stream))
    raw_audio_by_url, raw_manifest_paths = _load_raw_audio_manifests(roots)

    referenced_basenames = set()
    for row in rows:
        for field in ("file_audio", "file_script", "file_cau_hoi_dap_an", "file_hinh"):
            value = row.get(field, "")
            if value:
                referenced_basenames.add(Path(value).name.casefold())
        raw_audio_name = _url_basename(row.get("source_audio_url", ""))
        if raw_audio_name:
            referenced_basenames.add(raw_audio_name.casefold())
        for raw_manifest_name in raw_audio_by_url.get(row.get("source_audio_url", "").strip(), set()):
            referenced_basenames.add(raw_manifest_name.casefold())
    catalog_asset_conflicts = [
        conflict for conflict in conflicts
        if conflict["basename"].casefold() in referenced_basenames
    ]

    candidates = []
    issues = []
    coverage = Counter()
    unique_audio = {}
    for row_number, row in enumerate(rows, 2):
        audio, audio_matches = _resolve(by_basename, row.get("file_audio", ""))
        audio_resolution_source = "STANDARDIZED_FILE" if audio else ""
        if not audio:
            raw_audio_name = _url_basename(row.get("source_audio_url", ""))
            raw_audio, raw_audio_matches = _resolve(by_basename, raw_audio_name)
            if raw_audio:
                audio = raw_audio
                audio_matches = raw_audio_matches
                audio_resolution_source = "SOURCE_AUDIO_URL"
        resolved_from_manifest = []
        manifest_matches = []
        source_audio_url = row.get("source_audio_url", "").strip()
        for manifest_name in sorted(raw_audio_by_url.get(source_audio_url, set())):
            resolved, matches = _resolve(by_basename, manifest_name)
            manifest_matches.extend(matches)
            if resolved:
                resolved_from_manifest.append(resolved)
        unique_manifest_audio = {item["sha256"]: item for item in resolved_from_manifest}
        manifest_audio_conflict = len(unique_manifest_audio) > 1
        standardized_raw_conflict = False
        if manifest_audio_conflict:
            audio = None
            audio_matches = manifest_matches
            audio_resolution_source = ""
        elif len(unique_manifest_audio) == 1:
            manifest_audio = next(iter(unique_manifest_audio.values()))
            if audio and audio["sha256"] != manifest_audio["sha256"]:
                standardized_raw_conflict = True
                audio_matches = audio_matches + manifest_matches
                audio = None
                audio_resolution_source = ""
            elif not audio:
                audio = manifest_audio
                audio_matches = manifest_matches
                audio_resolution_source = "RAW_LISTENING_MANIFEST"
        script, script_matches = _resolve(by_basename, row.get("file_script", ""))
        question, question_matches = _resolve(by_basename, row.get("file_cau_hoi_dap_an", ""))
        image, image_matches = _resolve(by_basename, row.get("file_hinh", ""))
        parsed = parse_question_text(_read_text(question)) if question else {
            "prompt_ja": "", "source_label": "", "source_instruction": "", "options": [],
            "correct_option_order": None, "correct_answer": None, "source_url_in_file": "",
            "audio_only_options": False,
        }
        code = _code(row)
        level = row.get("cap_do", "").strip()
        exam_type = row.get("loai_de", "").strip()
        coverage[f"{level}:{exam_type}"] += 1

        row_issues = []
        for kind, source_value, resolved, matches in (
            ("AUDIO", row.get("file_audio", ""), audio, audio_matches),
            ("SCRIPT", row.get("file_script", ""), script, script_matches),
            ("QUESTION", row.get("file_cau_hoi_dap_an", ""), question, question_matches),
            ("IMAGE", row.get("file_hinh", ""), image, image_matches),
        ):
            if source_value and not resolved:
                issue_type = "CONFLICTING_LOCAL_FILE" if matches else "MISSING_LOCAL_FILE"
                row_issues.append(issue_type + "_" + kind)
                issues.append({"row": row_number, "question_code": code, "severity": "ERROR",
                               "issue_type": issue_type, "asset_type": kind,
                               "source_path": source_value, "matching_paths": [item["path"] for item in matches]})
        if audio and not valid_mp3_header(Path(audio["path"])):
            row_issues.append("INVALID_MP3_HEADER")
            issues.append({"row": row_number, "question_code": code, "severity": "ERROR",
                           "issue_type": "INVALID_MP3_HEADER", "asset_type": "AUDIO",
                           "source_path": audio["path"]})
        if audio_resolution_source == "SOURCE_AUDIO_URL":
            row_issues.append("STANDARDIZED_AUDIO_MISSING_RAW_SOURCE_USED")
        if audio_resolution_source == "RAW_LISTENING_MANIFEST":
            row_issues.append("STANDARDIZED_AUDIO_MISSING_RAW_SOURCE_USED")
        if manifest_audio_conflict:
            row_issues.append("CONFLICTING_RAW_AUDIO_MANIFEST")
            issues.append({"row": row_number, "question_code": code, "severity": "ERROR",
                           "issue_type": "CONFLICTING_RAW_AUDIO_MANIFEST", "asset_type": "AUDIO",
                           "source_path": row.get("source_audio_url", ""),
                           "matching_paths": [item["path"] for item in audio_matches]})
        if standardized_raw_conflict:
            row_issues.append("CONFLICTING_STANDARDIZED_AND_RAW_AUDIO")
            issues.append({"row": row_number, "question_code": code, "severity": "ERROR",
                           "issue_type": "CONFLICTING_STANDARDIZED_AND_RAW_AUDIO", "asset_type": "AUDIO",
                           "source_path": row.get("source_audio_url", ""),
                           "matching_paths": [item["path"] for item in audio_matches]})
        if not row.get("file_audio", "").strip() and not row.get("source_audio_url", "").strip():
            row_issues.append("MISSING_AUDIO_REFERENCE")
            issues.append({"row": row_number, "question_code": code, "severity": "ERROR",
                           "issue_type": "MISSING_AUDIO_REFERENCE", "asset_type": "AUDIO",
                           "source_path": ""})
        elif not audio and not row.get("file_audio", "").strip():
            row_issues.append("MISSING_LOCAL_FILE_AUDIO")
            issues.append({"row": row_number, "question_code": code, "severity": "ERROR",
                           "issue_type": "MISSING_LOCAL_FILE", "asset_type": "AUDIO",
                           "source_path": row.get("source_audio_url", ""), "matching_paths": []})
        if not parsed["prompt_ja"]:
            row_issues.append("MISSING_PROMPT")
        if not parsed["options"]:
            row_issues.append("MISSING_OPTIONS")
        if not parsed["correct_answer"]:
            row_issues.append("MISSING_OR_INVALID_ANSWER")
        if row.get("trang_thai_script") != "source_text_available":
            row_issues.append("SCRIPT_NOT_USABLE")
        row_issues.extend(["SEMANTIC_REVIEW_REQUIRED", "EXPLANATION_VI_MISSING"])

        audio_code = ""
        if audio:
            audio_code = f"MAZII-AUDIO-{audio['sha256'][:16].upper()}"
            unique_audio.setdefault(audio_code, {
                "audio_code": audio_code,
                "source_code": "MAZII-LISTENING-2026-10-02",
                "file_name": audio["basename"],
                "local_path": audio["path"],
                "source_url": row.get("source_audio_url", ""),
                "checksum": audio["sha256"],
                "bytes": audio["bytes"],
                "rights_status": OWNER_RIGHTS_STATUS,
                "rights_evidence_ref": OWNER_RIGHTS_EVIDENCE_REF,
                "license_note": "Owner confirmed J-LAS may store, clean, map and use this source; preserve attribution.",
                "attribution_note": "Source: Mazii/Migii handoff; retain source URL and source identifiers.",
                "playback_verified": False,
                "license_verified": True,
                "review_status": "NOT_REVIEWED",
                "reviewer": "",
                "reviewed_at": "",
                "review_evidence_ref": "",
                "ready_for_import": False,
            })
        usage = classify_candidate_usage(exam_type)
        candidate = {
            "question_code": code,
            "source_code": "MAZII-LISTENING-2026-10-02",
            "skill": "LISTENING",
            "jlpt_level": level,
            "exam_type": exam_type,
            **usage,
            "source_exam_id": row.get("id_de_nguon", ""),
            "source_question_id": row.get("id_cau_nguon", ""),
            "mondai": row.get("mondai", ""),
            "source_section": row.get("loai_mondai", ""),
            "question_type": "SINGLE_CHOICE",
            "prompt_ja": parsed["prompt_ja"],
            "options": parsed["options"],
            "correct_option_order": parsed["correct_option_order"],
            "correct_answer": parsed["correct_answer"] or "",
            "audio_only_options": parsed["audio_only_options"],
            "audio_code": audio_code,
            "audio_path": audio["path"] if audio else "",
            "audio_resolution_source": audio_resolution_source,
            "source_audio_url": row.get("source_audio_url", ""),
            "script_path": script["path"] if script else "",
            "question_source_path": question["path"] if question else "",
            "image_path": image["path"] if image else "",
            "source_url": row.get("source_url", ""),
            "external_key": f"MAZII:{exam_type}:{row.get('id_de_nguon', '')}:{row.get('id_cau_nguon', '')}:{row.get('so_cau_con_nguon', '')}",
            "confidence": "LOW" if row_issues else "HIGH",
            "rights_status": OWNER_RIGHTS_STATUS,
            "rights_evidence_ref": OWNER_RIGHTS_EVIDENCE_REF,
            "cleaning_status": "NEEDS_CLEANING",
            "review_status": "NOT_REVIEWED",
            "reviewer": "",
            "reviewed_at": "",
            "review_evidence_ref": "",
            "lifecycle_status": "DRAFT",
            "playback_verified": False,
            "answer_key_verified": False,
            "provenance_complete": False,
            "semantic_reviewed": False,
            "license_verified": True,
            "explanation_vi": "",
            "ready_for_import": False,
        }
        candidate["issues"] = sorted(set(row_issues + import_readiness_blockers(candidate)))
        candidate["ready_for_import"] = not candidate["issues"]
        candidates.append(candidate)

    summary = {
        "roots": len(roots),
        "inventory_files": len(inventory),
        "inventory_bytes": sum(entry["bytes"] for entry in inventory),
        "unique_content_hashes": len({entry["sha256"] for entry in inventory}),
        "duplicate_copies": duplicate_copies,
        "generic_conflicting_basenames": len(conflicts),
        "conflicting_catalog_asset_basenames": len(catalog_asset_conflicts),
        "catalog_rows": len(rows),
        "raw_listening_manifests": len(raw_manifest_paths),
        "raw_audio_manifest_urls": len(raw_audio_by_url),
        "audio_files_resolved": sum(bool(row["audio_path"]) for row in candidates),
        "script_files_resolved": sum(bool(row["script_path"]) for row in candidates),
        "question_files_resolved": sum(bool(row["question_source_path"]) for row in candidates),
        "image_files_resolved": sum(bool(row["image_path"]) for row in candidates),
        "unique_audio_resolved": len(unique_audio),
        "questions_with_prompt": sum(bool(row["prompt_ja"]) for row in candidates),
        "questions_with_options": sum(bool(row["options"]) for row in candidates),
        "questions_with_answer": sum(bool(row["correct_answer"]) for row in candidates),
        "source_script_unusable": sum("SCRIPT_NOT_USABLE" in row["issues"] for row in candidates),
        "rights_owner_confirmed": sum(row["rights_status"] == OWNER_RIGHTS_STATUS for row in candidates),
        "jlct_practice_only": sum(row["source_exam_type"] == "JLCT" and row["target_usage"] == "PRACTICE_ONLY"
                                    for row in candidates),
        "ready_for_import": sum(row["ready_for_import"] for row in candidates),
    }
    return {
        "policy": {
            "production_import_allowed": False,
            "rights_status": OWNER_RIGHTS_STATUS,
            "rights_evidence_ref": OWNER_RIGHTS_EVIDENCE_REF,
            "jlct_target_usage": "PRACTICE_ONLY",
            "jlct_jlpt_gap_fill_allowed": True,
            "jlct_jlpt_blueprint_allowed": False,
            "reason": "Cleaning, mapping, playback, answer, provenance, semantic and approval gates are not complete.",
        },
        "catalog_path": str(catalog_path) if catalog_path else None,
        "raw_audio_manifest_paths": raw_manifest_paths,
        "summary": summary,
        "coverage": dict(sorted(coverage.items())),
        "extension_counts": dict(sorted(Counter(entry["extension"] for entry in inventory).items())),
        "inventory": inventory,
        "conflicts": conflicts,
        "catalog_asset_conflicts": catalog_asset_conflicts,
        "issues": issues,
        "media_candidates": list(unique_audio.values()),
        "candidates": candidates,
    }


def write_outputs(result: dict, output: Path) -> None:
    output.mkdir(parents=True, exist_ok=False)
    (output / "mazii-listening-audit.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    policy = result["policy"]
    summary = result["summary"]
    coverage_rows = "\n".join(
        f"| {key.replace(':', ' | ')} | {count} |"
        for key, count in sorted(result.get("coverage", {}).items())
    ) or "| Không có | Không có | 0 |"
    report = f"""# Báo cáo audit dữ liệu Mazii Listening

## Policy đã duyệt

- Quyền sử dụng: `{policy['rights_status']}`; evidence `{policy['rights_evidence_ref']}`.
- JLCT: `PRACTICE_ONLY`; được bổ sung phần luyện tập JLPT còn thiếu sau clean, mapping và review.
- JLCT giữ nguyên `source_exam_type=JLCT` và không được đưa vào JLPT Smart Test/Mock Test.
- Production import: `{str(policy['production_import_allowed']).upper()}` — {policy['reason']}

## Kết quả

- Candidate: {summary.get('catalog_rows', 0)}.
- Owner rights confirmed: {summary.get('rights_owner_confirmed', 0)}.
- JLCT practice-only: {summary.get('jlct_practice_only', 0)}.
- Import-ready: {summary.get('ready_for_import', 0)}.

## Coverage nguồn

| Level | Source exam type | Candidate |
|---|---|---:|
{coverage_rows}

## Điều kiện chuyển trạng thái

Mỗi dòng phải hoàn tất cleaning, mapping, playback, answer-key, provenance, semantic review và approval.
Không dùng placeholder hoặc đổi nhãn JLCT thành JLPT để vượt gate.
"""
    (output / "MAZII_LISTENING_AUDIT_REPORT.md").write_text(report, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("roots", nargs="+", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = audit_roots(args.roots)
    write_outputs(result, args.output.resolve())
    print(json.dumps({"output": str(args.output.resolve()), **result["summary"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
