"""Assemble Kitsu's painted 6x5 atlases into native looping WebP animations.

Requires ffmpeg on PATH and Pillow for validation; does not draw or generate art.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
STATES = ("crying", "thinking", "confused", "resting", "cheer", "celebrate")
OUTPUT = ROOT / "src/main/resources/static/images/mascots/kitsu"


def run(*args):
    subprocess.run(["ffmpeg", "-v", "error", "-y", *map(str, args)], check=True)


def build(state):
    source = ROOT / f"data/mascot/kitsu-{state}-30/source-sheet.png"
    frames = source.parent / "frames"
    frames.mkdir(parents=True, exist_ok=True)
    with Image.open(source) as sheet:
        if sheet.width % 6 or sheet.height % 5 or sheet.width // 6 != sheet.height // 5:
            raise ValueError(f"{source}: expected six columns and five rows of square cells")
    layout_file = source.parent / "source-layout.json"
    if layout_file.exists():
        layout = json.loads(layout_file.read_text(encoding="utf-8"))
        columns, rows = layout["columns"], layout["rows"]
        column_rows = layout.get("rowsByColumn", [rows] * 6)
        if len(columns) != 7 or len(rows) != 6:
            raise ValueError(f"{layout_file}: expected 6 columns and 5 rows")
        for row in range(5):
            for column in range(6):
                boundaries = column_rows[column]
                x, y = columns[column], boundaries[row]
                width, height = columns[column + 1] - x, boundaries[row + 1] - y
                run("-i", source, "-vf",
                    f"crop={width}:{height}:{x}:{y},scale=224:224:force_original_aspect_ratio=decrease,"
                    "pad=256:256:(ow-iw)/2:(oh-ih)/2:color=0x00000000",
                    "-frames:v", 1, frames / f"frame-{row * 6 + column:02d}.png")
    else:
        run("-i", source, "-vf", "untile=6x5,scale=224:224,pad=256:256:16:16:color=0x00000000",
            "-fps_mode", "passthrough", "-frames:v", 30, "-start_number", 0, frames / "frame-%02d.png")
    destination = OUTPUT / f"kitsu-{state}-30.webp"
    run("-framerate", 12, "-start_number", 0, "-i", frames / "frame-%02d.png",
        "-frames:v", 30, "-c:v", "libwebp_anim", "-lossless", 0, "-quality", 90,
        "-compression_level", 4, "-loop", 0, destination)
    with Image.open(destination) as animation:
        if animation.n_frames != 30 or animation.info.get("loop") != 0:
            raise ValueError(f"{destination}: expected 30 infinitely looping frames")
        hashes = set()
        for frame in range(animation.n_frames):
            animation.seek(frame)
            pixels = animation.convert("RGBA")
            hashes.add(hashlib.sha256(pixels.tobytes()).digest())
            if pixels.getchannel("A").getextrema() != (0, 255):
                raise ValueError(f"{destination}: missing transparent background")
        if len(hashes) != 30:
            raise ValueError(f"{destination}: found repeated frames")
    print(f"{state}: 30 unique frames, 12 fps, transparent, infinite loop, {destination.stat().st_size} bytes", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("states", nargs="*", choices=STATES, default=list(STATES))
    for state in parser.parse_args().states:
        build(state)
