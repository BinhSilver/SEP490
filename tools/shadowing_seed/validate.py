"""Offline editorial/contract checks. No model, speech API, or database calls."""
from __future__ import annotations

from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import unicodedata

from captions import CACHE, cues

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data/shadowing"
KINDS = {"GAP", "DETAIL", "MAIN_IDEA", "ACTION_SEQUENCE", "REASON_PURPOSE",
         "PROBLEM_SOLUTION", "ATTITUDE_DECISION"}


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def spoken(text):
    text = unicodedata.normalize("NFKC", text)
    return re.sub(r"\s+", " ", re.sub(r"\[[^\]]*\]|【[^】]*】", "", text)).strip()


def key(text):
    return "".join(c for c in text.lower() if unicodedata.category(c)[0] not in "PZS")


def japanese(text):
    # Match Java's HAN/HIRAGANA/KATAKANA scripts: prolonged marks and middle dots
    # have script COMMON and must not inflate the minimum visible context.
    return len(re.findall(r"[\u3040-\u30ff\u3400-\u9fff]", text.translate(
        dict.fromkeys(map(ord, '\u309b\u309c\u30a0\u30fb\u30fc'), None))))


def load_payload(caption_dir=CACHE):
    """Accept normalized local <id>.cues.json, or the collector's <id>.json cache."""
    result = []
    reviewed_path=DATA/'editorial/reviewed_cues.json'
    reviewed=read_json(reviewed_path) if reviewed_path.exists() else []
    for video in read_json(DATA / "catalog.json")["videos"]:
        source = video["source_key"]
        if not video["expected_cue_count"]:
            continue
        normalized = (DATA / "local" / f"{source}.cues.json" if video.get("source_type") == "LOCAL"
                      else Path(caption_dir) / f"{source}.cues.json")
        if normalized.exists():
            rows = read_json(normalized)
        elif any(r['source_key']==source for r in reviewed):
            rows = [r for r in reviewed if r['source_key']==source]
        else:
            rows = cues(read_json(Path(caption_dir) / f"{source}.json"))
        for row in rows:
            result.append(dict(row, source_key=source))
    return result


def fingerprints(payload):
    return [{k: row[k] for k in ("source_key", "order", "start", "end")}
            | {"text_sha256": digest(row["text"])} for row in payload]


def materialize(question, rows):
    by_order = {s["order"]: s for s in rows}
    evidence = [by_order[n] for n in range(question["first_order"], question["last_order"] + 1)]
    row = by_order[question["answer_order"]]
    offset = question["answer_start"] - 1
    answer = row["text"][offset:offset + question["answer_length"]]
    if question["kind"] == "GAP":
        choices = []
        if question["level"] == "HARD":
            prompt = "音声の一文を日本語で書いてください。"
        else:
            prompt = "音声を聞いて、空欄に入る言葉は何ですか。\n" + row["text"][:offset]
            prompt += "＿" * max(3, min(12, len(answer))) + row["text"][offset + len(answer):]
    else:
        prompt = question["prompt"]
        choices = list(question["distractors"])
        choices.insert(question["correct_position"] - 1, answer)
    return dict(question, answer=answer, rendered_prompt=prompt, choices=choices, evidence=evidence)


def validate(payload, *, check_fingerprints=True, require_enrichment=False):
    catalog = read_json(DATA / "catalog.json")
    questions = read_json(DATA / "questions.json")["questions"]
    videos = {v["source_key"]: v for v in catalog["videos"]}
    assert len(videos) == len(catalog["videos"]), "Duplicate video business key"
    assert len({q["key"] for q in questions}) == len(questions), "Duplicate question key"
    assert len({s["key"] for s in catalog["series"]}) == len(catalog["series"])
    grouped = {v: [] for v in videos}
    for row in payload:
        assert row["source_key"] in grouped, "Unknown source in caption payload"
        grouped[row["source_key"]].append(row)
    for source, rows in grouped.items():
        video = videos[source]
        assert len(rows) == video["expected_cue_count"], f"{source}: wrong caption count"
        assert [r["order"] for r in rows] == list(range(1, len(rows) + 1)), f"{source}: cue order"
        previous_end = 0
        for row in rows:
            assert 0 <= row["start"] < row["end"] <= video["duration_seconds"], f"{source}: cue time"
            assert row["start"] >= previous_end, f"{source}: overlapping cues"
            assert row["text"].strip(), f"{source}: blank cue"
            if require_enrichment:
                assert (row.get("furigana") or "").strip(), f"{source}: missing reading at cue {row['order']}"
                assert (row.get("translation") or "").strip(), f"{source}: missing translation at cue {row['order']}"
            previous_end = row["end"]
    if check_fingerprints:
        assert fingerprints(payload) == read_json(DATA / "caption_fingerprints.json"), \
            "Caption text/timing changed: review questions and regenerate fingerprints before import"
    for series in catalog["series"]:
        assert series["members"] and len(set(series["members"])) == len(series["members"])
        assert set(series["members"]) <= videos.keys()
    materialized = []
    errors = []
    for q in questions:
        try:
            assert q["kind"] in KINDS and q["level"] in {"EASY", "MEDIUM", "HARD"}
            assert 1 <= q["first_order"] <= q["answer_order"] <= q["last_order"]
            assert q["last_order"] - q["first_order"] < 6, "More than six evidence cues"
            m = materialize(q, grouped[q["source_key"]])
            answer, evidence = m["answer"], m["evidence"]
            assert not any(r.get('requires_audio_review') for r in evidence), "Evidence still requires audio review"
            assert len(answer) == q["answer_length"] and japanese(answer), "Invalid answer slice"
            assert any(answer in spoken(r["text"]) for r in evidence), "Answer absent from spoken evidence"
            assert evidence[0]["start"] == q["anchor_start"] and evidence[-1]["end"] == q["anchor_end"]
            assert q["explanation_vi"].strip(), "Missing explanation"
            if q["kind"] == "GAP":
                assert len(evidence) == 1
                line = spoken(evidence[0]["text"])
                assert line.count(answer) == 1, "Gap answer occurs more than once"
                n, a = len(line), len(answer)
                if q["level"] == "HARD":
                    assert key(line) == key(answer) and 12 <= n <= 90, "Dictation is not a complete useful cue"
                    assert q["anchor_end"] - q["anchor_start"] <= 18
                else:
                    assert japanese(m["rendered_prompt"].split("\n")[-1].replace("＿", "")) >= 4
                    assert answer not in m["rendered_prompt"], "Answer leaked by gap prompt"
                    if q["level"] == "EASY":
                        assert a < n and a <= max(4, -(-n * 45 // 100)), "Easy gap too long"
                    else:
                        assert 5 <= a < n and n * .30 <= a <= n * .65, "Medium gap ratio"
            else:
                assert japanese(q["prompt"]) >= 4 and q["prompt"].endswith(("？", "?", "か。"))
                assert answer not in q["prompt"], "Answer leaked by question"
                assert len(m["choices"]) == len(set(m["choices"])) == 4
                assert all(japanese(c) for c in m["choices"])
                source = " ".join(spoken(r["text"]) for r in evidence)
                if q["kind"] == "REASON_PURPOSE":
                    assert any(c in answer for c in ("から", "ので", "ため", "せい", "おかげ", "理由"))
                if q["kind"] == "ACTION_SEQUENCE":
                    assert re.search(r"[いきぎしちにびみりべえけせてねめれ](?:ます|ました)|します|しました|して|行って|食べて|飲んで|帰って|取って|買って|見て|作って|た後|てから", source), "No action evidence"
                    assert any(c in source for c in ("まず", "最初", "次に", "その後", "それから", "続いて", "先に", "後で", "あとで", "前に", "てから", "た後"))
            for prev in materialized:
                if prev["source_key"] != q["source_key"] or prev["kind"] != q["kind"]:
                    continue
                assert q["first_order"] > prev["last_order"] or q["last_order"] < prev["first_order"], "Overlapping same-kind evidence"
                left, right = key(answer), key(prev["answer"])
                assert left != right and not (min(len(left), len(right)) >= 6 and (left in right or right in left)), "Duplicate answer"
            materialized.append(m)
        except (AssertionError, KeyError) as e:
            errors.append(f"{q['key']}: {e or 'contract check failed'}")
    if errors:
        raise ValueError("\n".join(errors))
    return {"videos": len(videos), "series": len(catalog["series"]),
            "memberships": sum(len(s["members"]) for s in catalog["series"]),
            "questions": len(questions), "cues": len(payload),
            "kinds": dict(Counter(q["kind"] for q in questions)),
            "levels": dict(Counter(q["level"] for q in questions))}


if __name__ == "__main__":
    print(json.dumps(validate(load_payload()), ensure_ascii=True, indent=2))
