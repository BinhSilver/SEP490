"""Transcribe local media on CPU or a local GPU; never sends audio to an API.

Install faster-whisper in an isolated environment first. Raw recognition output
is evidence for editorial review, not automatically approved production content.
Model weights are downloaded once into the ignored local cache.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import wave

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / "data/shadowing/.cache"


def _add_cuda_dlls():
    """On Windows the CUDA wheels keep their DLLs under site-packages/nvidia/*/bin, off the search path."""
    import os
    import sys
    for base in map(Path, sys.path):
        for bin_dir in (base / "nvidia").glob("*/bin"):
            os.add_dll_directory(str(bin_dir))
            os.environ["PATH"] = str(bin_dir) + os.pathsep + os.environ.get("PATH", "")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--model", default="small")
    parser.add_argument("--model-dir", type=Path, help="Use already downloaded model weights")
    parser.add_argument("--language", help="Omit to detect the actual spoken language")
    parser.add_argument("--no-vad", action="store_true", help="Cross-check speech mixed with anime music")
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu",
                        help="cuda needs the nvidia-cublas-cu12 and nvidia-cudnn-cu12 wheels in this environment")
    args = parser.parse_args()
    if args.device == "cuda":
        _add_cuda_dlls()
    from faster_whisper import WhisperModel
    model = WhisperModel(str(args.model_dir) if args.model_dir else args.model, device=args.device,
                         compute_type="int8" if args.device == "cpu" else "int8_float16", cpu_threads=8,
                         download_root=str(CACHE / "asr-models"))
    output = CACHE / "asr"
    output.mkdir(parents=True, exist_ok=True)
    (output / "wav").mkdir(exist_ok=True)
    for path in args.paths:
        import numpy as np
        wav = output / "wav" / f"{path.stem}.wav"
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(path.resolve(strict=True)),
                        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", str(wav)],
                       check=True, timeout=90)
        with wave.open(str(wav), "rb") as stream:
            audio = np.frombuffer(stream.readframes(stream.getnframes()), dtype="<i2").astype(np.float32)/32768
        segments, info = model.transcribe(audio, language=args.language,
            beam_size=5, word_timestamps=True, vad_filter=not args.no_vad, condition_on_previous_text=False)
        rows = [dict(start=s.start, end=s.end, text=s.text.strip(),
                     avg_logprob=s.avg_logprob, no_speech_prob=s.no_speech_prob,
                     words=[dict(start=w.start, end=w.end, text=w.word,
                                 probability=w.probability) for w in s.words or []]) for s in segments]
        result = dict(file=path.name, model=args.model, language=info.language,
                      language_probability=info.language_probability, segments=rows)
        target = output / f"{path.stem}.{args.model}{'-no-vad' if args.no_vad else ''}.json"
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(dict(file=path.name, language=info.language,
                              probability=round(info.language_probability, 3),
                              segments=len(rows), output=str(target)), ensure_ascii=True), flush=True)


if __name__ == "__main__":
    main()
