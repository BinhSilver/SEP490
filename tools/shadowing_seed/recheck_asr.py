"""Third listen for disputed lines: re-transcribe only those clips with a different Whisper model.

Reads data/shadowing/.cache/asr/compare/<video>.json (from compare_asr.py), cuts each MINOR/MAJOR segment
(plus a short margin) out of the 16 kHz WAV that transcribe_local.py left behind and transcribes the clip on
its own, so the model hears the line without the surrounding music or speech. Writes <video>.recheck.json
next to the comparison; compare_asr.py adds it as a third column. Run inside the faster-whisper environment.
"""
from __future__ import annotations
import argparse
import json
import wave
from pathlib import Path

from transcribe_local import CACHE, _add_cuda_dlls

COMPARE = CACHE / "asr" / "compare"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video_id")
    parser.add_argument("--model", default="small")
    parser.add_argument("--model-dir", type=Path)
    parser.add_argument("--classes", default="MINOR,MAJOR")
    parser.add_argument("--margin", type=float, default=0.4)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    args = parser.parse_args()
    if args.device == "cuda":
        _add_cuda_dlls()
    import numpy as np
    from faster_whisper import WhisperModel
    rows = json.loads((COMPARE / f"{args.video_id}.json").read_text(encoding="utf-8"))["rows"]
    wanted = set(args.classes.split(","))
    with wave.open(str(CACHE / "asr" / "wav" / f"{args.video_id}.wav"), "rb") as stream:
        rate = stream.getframerate()
        audio = np.frombuffer(stream.readframes(stream.getnframes()), dtype="<i2").astype(np.float32) / 32768
    model = WhisperModel(str(args.model_dir) if args.model_dir else args.model, device=args.device,
                         compute_type="int8" if args.device == "cpu" else "int8_float16",
                         download_root=str(CACHE / "asr-models"))
    result = {}
    for row in rows:
        if row["class"] not in wanted:
            continue
        clip = audio[int(max(0, row["start"] - args.margin) * rate):int((row["end"] + args.margin) * rate)]
        segments, _ = model.transcribe(clip, language="ja", beam_size=5, vad_filter=False,
                                       condition_on_previous_text=False)
        result[str(row["n"])] = "".join(s.text for s in segments).strip()
    target = COMPARE / f"{args.video_id}.recheck.json"
    target.write_text(json.dumps({"model": args.model, "lines": result}, ensure_ascii=False, indent=1) + "\n",
                      encoding="utf-8")
    print(json.dumps({"video": args.video_id, "rechecked": len(result), "output": str(target)}))


if __name__ == "__main__":
    main()
