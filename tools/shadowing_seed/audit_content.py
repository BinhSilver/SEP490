"""Report actual content completeness separately from valid workflow NULLs."""
from __future__ import annotations
from collections import Counter
import json
from validate import DATA, load_payload, read_json, validate
from import_seed import media_input


def audit():
    payload = load_payload()
    report = validate(payload, require_enrichment=True)
    catalog = read_json(DATA / "catalog.json")
    questions = read_json(DATA / "questions.json")["questions"]
    media = {m["source_key"]: m for m in media_input(None)}
    rows = []
    for video in catalog["videos"]:
        source = video["source_key"]
        cues = [r for r in payload if r["source_key"] == source]
        qs = [q for q in questions if q["source_key"] == source]
        audio_review = [r['order'] for r in cues if r.get('requires_audio_review')]
        missing = []
        if not cues:
            missing += ["JAPANESE_TRANSCRIPT", "READINGS", "VIETNAMESE_TRANSLATIONS", "QUESTIONS"]
        if not video["estimated_jlpt"]:
            missing.append("JLPT_ESTIMATE")
        if source not in media:
            missing += ["LOCAL_PLAYABLE_MEDIA", "LOCAL_THUMBNAIL"]
        elif not media[source]["thumbnail_path"]:
            missing.append("LOCAL_THUMBNAIL")
        rows.append(dict(source_key=source, title=video["title"], cues=len(cues), questions=len(qs),
                         missing=missing, content_complete=bool(cues and qs and video["estimated_jlpt"]),
                         audio_review_cues=audio_review,
                         deployment_complete=not missing and not audio_review))
    report["sources"] = rows
    report["content_complete_sources"] = sum(r["content_complete"] for r in rows)
    report["deployment_complete_sources"] = sum(r["deployment_complete"] for r in rows)
    report["subtitle_readings"] = sum(bool(r.get("furigana")) for r in payload)
    report["subtitle_translations"] = sum(bool(r.get("translation")) for r in payload)
    report["translation_provenance"] = dict(Counter(r.get("translation_source", "UNSPECIFIED") for r in payload))
    report["legitimate_null_fields"] = {
        "video.error_message": "No processing error for a healthy or unstarted item.",
        "shadowing_preparation_job.error_message": "No preparation error on a completed item.",
        "shadowing_preparation_job.claim_token": "No worker owns this completed offline job.",
        "shadowing_preparation_job.lease_until": "No running worker lease.",
        "local.channel_url": "No original remote publisher URL supplied with the local file."
    }
    return report


if __name__ == "__main__":
    print(json.dumps(audit(), ensure_ascii=True, indent=2))
