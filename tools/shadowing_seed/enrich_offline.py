"""Fill local caption readings and Vietnamese translations without a hosted API.

Direct Japanese -> Vietnamese M2M100 translation runs on CPU. Output stays in
the ignored caption cache for public URL sources; user-provided local transcripts
stay in data/shadowing/local. Review model translations as editorial content.
Existing translations are preserved and interrupted runs resume by source.
"""
from __future__ import annotations
import argparse
import json
import re
import unicodedata
from pathlib import Path
from validate import CACHE, DATA, load_payload, read_json


_overrides_path = DATA / "reading_overrides.json"
_reading_overrides = read_json(_overrides_path) if _overrides_path.exists() else {}
_names_pattern = re.compile("(" + "|".join(re.escape(s) for s in sorted(_reading_overrides, key=len, reverse=True)) + ")") if _reading_overrides else None


def reading(text, tagger):
    if not _names_pattern:
        return _reading_tokens(text, tagger)
    return " ".join(
        f"{part}({_reading_overrides[part]})" if part in _reading_overrides else _reading_tokens(part, tagger)
        for part in _names_pattern.split(text) if part
    ).strip()


def _reading_tokens(text, tagger):
    parts = []
    for token in tagger(text):
        surface = str(token)
        kana = token.feature.kana
        if re.search(r"[\u3400-\u9fff]", surface) and kana:
            kana = unicodedata.normalize("NFKC", kana)
            hira = "".join(chr(ord(c)-96) if '\u30a1' <= c <= '\u30f6' else c for c in kana)
            if surface == "日本":
                hira = "にほん"
            # Whitespace bounds the base recognized by the application's ruby parser.
            parts.append(f" {surface}({hira}) ")
        else:
            parts.append(surface)
    return re.sub(r" +", " ", "".join(parts)).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", action="append", help="Only process these source keys")
    args = parser.parse_args()
    import fugashi
    import ctranslate2
    from transformers import M2M100Tokenizer
    tagger = fugashi.Tagger()
    model_root = CACHE / "mt-models"
    path = str(model_root / "m2m100-int8")
    tokenizer = M2M100Tokenizer.from_pretrained(path, local_files_only=True)
    tokenizer.src_lang = "ja"
    pair = (tokenizer, ctranslate2.Translator(path, device="cpu", compute_type="int8", intra_threads=8))

    def translate(lines, pair, prefix=""):
        tokenizer, model = pair
        output = []
        for start in range(0, len(lines), 32):
            inputs = [tokenizer.convert_ids_to_tokens(tokenizer.encode(prefix+s)) for s in lines[start:start+32]]
            if any(len(tokens)>512 for tokens in inputs):
                raise ValueError("Cue exceeds translation context; split and review first")
            result = model.translate_batch(inputs, target_prefix=[["__vi__"]]*len(inputs),
                                           beam_size=4, max_decoding_length=192,
                                           repetition_penalty=1.1, no_repeat_ngram_size=3)
            output += [tokenizer.decode(tokenizer.convert_tokens_to_ids(r.hypotheses[0]),
                                        skip_special_tokens=True) for r in result]
        return output

    payload = load_payload()
    for video in read_json(DATA / "catalog.json")["videos"]:
        source = video["source_key"]
        if args.source and source not in args.source:
            continue
        rows = [dict(r) for r in payload if r["source_key"] == source]
        if not rows:
            continue
        missing = [r for r in rows if not (r.get("translation") or "").strip()
                   or r.get("translation_source") == "OFFLINE_MARIAN_JA_EN_VI"]
        if missing:
            vietnamese = translate([r["text"] for r in missing], pair)
            for row, text in zip(missing, vietnamese, strict=True):
                if not text.strip():
                    print(json.dumps(dict(source=source, order=row['order'],
                                          translation_review="EMPTY_M2M_RESULT", text=row['text']),
                                     ensure_ascii=True),flush=True)
                    if (row.get('translation') or '').strip():
                        row['translation_source']='OFFLINE_MARIAN_FALLBACK_REVIEW'
                        continue
                    raise ValueError(f"{source}: empty model translation at cue {row['order']}")
                row["translation"] = text.strip()
                row["translation_source"] = "OFFLINE_M2M100_JA_VI"
        for row in rows:
            if not row.get("furigana") or video.get("source_type") != "LOCAL":
                row["furigana"] = reading(row["text"], tagger)
            row.pop("source_key", None)
        target_dir = DATA / "local" if video.get("source_type") == "LOCAL" else CACHE
        target = target_dir / f"{source}.cues.json"
        target.write_text(json.dumps(rows, ensure_ascii=False, indent=2)+"\n", encoding="utf-8", newline="\n")
        print(json.dumps(dict(source=source, cues=len(rows), translated=len(missing)), ensure_ascii=True), flush=True)


if __name__ == "__main__":
    main()
