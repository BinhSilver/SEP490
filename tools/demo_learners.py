"""Shared, deterministic demo learner identities for every engagement seed (Shadowing, Flashcard).

One list of full names (data/shadowing/demo_engagement.json -> "learners") becomes one set of
accounts, so the same person comments on a video and owns/rates a flashcard set. Email and
username are the name without diacritics plus a stable number, e.g. nguyenminhkhoi27@gmail.com.

The accounts are seeded without a password, unverified and LOCAL; seeds refuse to adopt an
existing login-capable account with the same email. legacy_* is the earlier reserved
.invalid identity, which seeds rename in place so existing demo activity is kept.
"""
from __future__ import annotations

from hashlib import sha256
import json
from pathlib import Path
import unicodedata

NAMES_FILE = Path(__file__).resolve().parents[1] / "data/shadowing/demo_engagement.json"
LEVELS = ("N5", "N4", "N4", "N3", "N3", "N2")


def number(*parts) -> int:
    return int(sha256("|".join(map(str, parts)).encode("utf-8")).hexdigest(), 16)


def ascii_slug(name: str) -> str:
    text = name.replace("Đ", "D").replace("đ", "d")
    text = "".join(c for c in unicodedata.normalize("NFD", text) if not unicodedata.combining(c))
    slug = "".join(c for c in text.lower() if c.isascii() and c.isalnum())
    if not slug:
        raise ValueError(f"Name has no ASCII letters: {name!r}")
    return slug


def suffix(name: str) -> str:
    # Mix the usual Gmail habits: birth year, two digits, three digits.
    value = number("jlas-demo-learner", name)
    style = value % 3
    if style == 0:
        return str(1995 + value // 3 % 12)
    if style == 1:
        return f"{10 + value // 3 % 90}"
    return f"{100 + value // 3 % 900}"


def build_learners(names: list[str]) -> list[dict]:
    if len(names) != len(set(names)) or not 20 <= len(names) <= 200:
        raise ValueError("Demo learners must have distinct full names; expected 20-200 profiles")
    learners = []
    for index, name in enumerate(names, 1):
        handle = ascii_slug(name) + suffix(name)
        learners.append({"key": f"learner-{index:03}", "name": name,
                         "email": f"{handle}@gmail.com", "username": handle[:50],
                         "legacy_email": f"shadowing-demo-{index:03}@seed.jlas.invalid",
                         "legacy_username": f"shadowing_demo_{index:03}",
                         "level": LEVELS[index % 6]})
    for field in ("email", "username"):
        if len({learner[field] for learner in learners}) != len(learners):
            raise ValueError(f"Demo learner {field} collision; adjust the suffix rule")
    return learners


def load_learners(source: dict | None = None) -> list[dict]:
    source = source or json.loads(NAMES_FILE.read_text(encoding="utf-8"))
    return build_learners(source["learners"])


if __name__ == "__main__":
    for learner in load_learners():
        print(learner["key"], learner["email"], learner["name"].encode("ascii", "replace").decode())
