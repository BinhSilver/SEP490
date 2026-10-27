"""Cross-check YouTube auto captions against local Whisper runs, segment by segment.

Neither source is trusted alone: a line is only accepted when independent recognisers agree on how it
sounds. For every Whisper segment (the main run) this collects the auto-caption words and the other Whisper
run (e.g. without voice-activity filtering) heard in the same time window, compares their kana readings and
classifies the segment by its agreement with the auto captions, the only independent recogniser:

  MATCH  (>= 0.90 reading similarity)  usable; pick the kanji spelling that fits the context
  MINOR  (>= 0.60)                     same sound, different spelling/particles: editor decides
  MAJOR  (<  0.60)                     needs a closer listen; if still unclear, requires_audio_review

Output: data/shadowing/.cache/asr/compare/<video>.json (+ a .tsv to read quickly). No network, no API.
"""
from __future__ import annotations
import argparse
import difflib
import json
import re
import unicodedata
from pathlib import Path

from captions import CACHE, clean_caption_text

OUTPUT = CACHE / "asr" / "compare"
MATCH, MINOR = 0.90, 0.60


def auto_words(metadata):
    """Auto caption words with their own start time (json3 segs carry per-word offsets)."""
    words = []
    for event in metadata.get("captions", {}).get("events", []):
        start = event.get("tStartMs", 0)
        for seg in event.get("segs", []) or []:
            text = clean_caption_text(seg.get("utf8", ""))
            if text and text != "\n":
                words.append(((start + seg.get("tOffsetMs", 0)) / 1000, text))
    return words


def to_hiragana(text):
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in text)


def reading(text, tagger):
    """Kana of what is said, without punctuation or spaces, so spelling differences do not count."""
    text = unicodedata.normalize("NFKC", text)
    parts = []
    for token in tagger(text):
        kana = token.feature.kana or str(token)
        parts.append(to_hiragana(unicodedata.normalize("NFKC", kana)))
    return re.sub(r"[^ぁ-ゖー0-9a-z]", "", "".join(parts).lower())


def similarity(line, window):
    """Share of the Whisper line's reading found, in order, in the other source's window.

    The window may hold a word or two of the neighbouring lines (caption timing is coarse), so extra text in
    the window does not lower the score; missing or different sounds do.
    """
    if not line:
        return 1.0 if not window else 0.0
    blocks = difflib.SequenceMatcher(None, line, window, autojunk=False).get_matching_blocks()
    return sum(block.size for block in blocks) / len(line)


def window_text(words, start, end, slack=0.35):
    return "".join(text for time, text in words if start - slack <= time < end + slack).strip()


def segment_text(segments, start, end):
    """Text of another Whisper run that overlaps this window by at least half of its own length."""
    picked = []
    for seg in segments:
        overlap = min(end, seg["end"]) - max(start, seg["start"])
        if overlap > 0 and overlap >= 0.5 * (seg["end"] - seg["start"]):
            picked.append(seg["text"])
    return "".join(picked).strip()


def compare(video_id, main_run, other_run=None):
    import fugashi
    tagger = fugashi.Tagger()
    metadata = json.loads((CACHE / f"{video_id}.json").read_text(encoding="utf-8"))
    words = auto_words(metadata)
    main = json.loads(Path(main_run).read_text(encoding="utf-8"))["segments"]
    other = json.loads(Path(other_run).read_text(encoding="utf-8"))["segments"] if other_run else []
    recheck_path = OUTPUT / f"{video_id}.recheck.json"
    recheck = json.loads(recheck_path.read_text(encoding="utf-8"))["lines"] if recheck_path.exists() else {}
    rows = []
    for index, seg in enumerate(main, 1):
        start, end, text = round(seg["start"], 2), round(seg["end"], 2), seg["text"].strip()
        auto = window_text(words, start, end)
        alt = segment_text(other, start, end) if other else ""
        mine = reading(text, tagger)
        score_auto = similarity(mine, reading(auto, tagger))
        score_alt = similarity(mine, reading(alt, tagger)) if other else None
        # Only the auto captions are an independent recogniser; a second Whisper run of the same model mostly
        # repeats its own mistakes, so it can confirm the timing of speech but never turn a line into MATCH.
        independent = score_auto if auto else min(score_alt or 0, MATCH - 0.01)
        rows.append({
            "n": index, "start": start, "end": end, "whisper": text, "auto": auto, "whisper_alt": alt,
            "sim_auto": round(score_auto, 3), "sim_alt": None if score_alt is None else round(score_alt, 3),
            "class": "MATCH" if independent >= MATCH else "MINOR" if independent >= MINOR else "MAJOR",
            "avg_logprob": round(seg.get("avg_logprob", 0), 3), "no_speech_prob": round(seg.get("no_speech_prob", 0), 3),
        })
        third = recheck.get(str(index))
        if third is not None:
            # A different model on the isolated clip: an independent third listen.
            rows[-1]["recheck"] = third
            rows[-1]["sim_recheck"] = round(similarity(mine, reading(third, tagger)), 3)
    # Auto-caption speech the main Whisper run skipped entirely (often quiet lines under music).
    covered = [(r["start"] - 0.35, r["end"] + 0.35) for r in rows]
    missed = [(t, w) for t, w in words if not any(a <= t < b for a, b in covered)]
    OUTPUT.mkdir(parents=True, exist_ok=True)
    summary = {
        "video": video_id, "segments": len(rows),
        "classes": {c: sum(1 for r in rows if r["class"] == c) for c in ("MATCH", "MINOR", "MAJOR")},
        "auto_words_outside_whisper": len(missed),
    }
    result = {"summary": summary, "rows": rows, "missed_auto_words": [{"time": round(t, 2), "text": w} for t, w in missed]}
    (OUTPUT / f"{video_id}.json").write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    with (OUTPUT / f"{video_id}.tsv").open("w", encoding="utf-8", newline="\n") as tsv:
        tsv.write("n\tstart\tend\tclass\tsim_auto\tsim_alt\tsim_recheck\twhisper\tauto\twhisper_alt\trecheck\n")
        for r in rows:
            tsv.write(f"{r['n']}\t{r['start']}\t{r['end']}\t{r['class']}\t{r['sim_auto']}\t{r['sim_alt']}\t"
                      f"{r.get('sim_recheck', '')}\t{r['whisper']}\t{r['auto']}\t{r['whisper_alt']}\t{r.get('recheck', '')}\n")
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("video_id")
    parser.add_argument("main_run", help="Whisper JSON from transcribe_local.py (e.g. <id>.large-v3-turbo.json)")
    parser.add_argument("--other-run", help="A second Whisper JSON for the same audio (e.g. the -no-vad run)")
    args = parser.parse_args()
    print(json.dumps(compare(args.video_id, args.main_run, args.other_run), ensure_ascii=False))


if __name__ == "__main__":
    main()
