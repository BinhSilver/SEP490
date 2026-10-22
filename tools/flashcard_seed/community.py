"""Deterministic community flashcard fixtures (no network/AI): sets, cards, ratings, studies, comments.

Inputs (editable):
  data/flashcard/vocab_pool.json     - clean N5-N4 words exported by extract_pool.py
  data/flashcard/community_sets.json - set definitions, star histograms, favorites, comments
  tools/demo_learners.py             - the shared demo learner identities (same as Shadowing)
"""
from __future__ import annotations

from hashlib import sha256
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from demo_learners import load_learners  # noqa: E402

DATA = Path(__file__).resolve().parents[2] / "data/flashcard"
MIN_CARDS = 20
KATAKANA = re.compile(r"^[゠-ヿー]+$")
KANJI = re.compile(r"[一-鿿々]")
JAPANESE = re.compile(r"[぀-ヿ一-鿿々ー～]{2,}")
GRAMMAR_TERMS = {"ます"}  # quoted as grammar notation (thể ます), not as a vocabulary card


def number(*parts):
    return int(sha256("|".join(map(str, parts)).encode("utf-8")).hexdigest(), 16)


def ranked(values, *salt):
    return sorted(values, key=lambda value: (number(*salt, value), str(value)))


def is_katakana(word):
    return bool(KATAKANA.match(word["expression"]))


def is_verb(word):
    if word["source"] == "SEKAI":
        return word["reading"].endswith("ます")
    gloss = word.get("gloss_en") or ""
    return gloss.startswith("to ") and word["reading"][-1] in "うくぐすつぬぶむる"


def is_derived(word):
    """Grammar forms the textbook lists as vocabulary (passive/causative/potential/plain)."""
    reading, meaning = word["reading"], word["meaning"]
    return (reading.endswith(("すぎます", "させます", "なら"))
            or (reading.endswith("せます") and meaning.startswith(("bắt", "cho ")))
            or (reading.endswith("れます") and ("được" in meaning or meaning.startswith("bị")))
            or (reading.endswith("だ") and "nói thân" in meaning))


def han_viet(expression, table, words=None):
    """Compose per-kanji Hán Việt (kanji table, first reading); `words` fixes known compounds."""
    if not KANJI.search(expression):
        return None
    if words and expression in words:
        return words[expression]
    parts, previous = [], None
    for char in expression:
        if char == "々":
            char = previous
        if char is None or not KANJI.match(char):
            previous = char
            continue
        if not table.get(char):  # unknown, or blanked by an override (kokuji without a reading)
            return None
        parts.append(table[char].split(",")[0].strip().upper())
        previous = char
    return " ".join(parts) or None


def word_type(word):
    if is_verb(word):
        return "Động từ"
    if is_katakana(word):
        return "Từ mượn (katakana)"
    return None


def candidates(words, selector):
    levels = set(selector.get("levels", ("N5", "N4")))
    chosen = [w for w in words if w["level"] in levels]
    if "source" in selector:
        chosen = [w for w in chosen if w["source"] == selector["source"]]
    if selector.get("katakana"):
        chosen = [w for w in chosen if is_katakana(w)]
    if selector.get("verbs"):
        chosen = [w for w in chosen if is_verb(w)]
    if selector.get("kanji"):
        chosen = [w for w in chosen if KANJI.search(w["expression"])]
    if selector.get("examples"):
        chosen = [w for w in chosen if w["example_ja"]]
    if "lessons" in selector:
        book, numbers = selector["lessons"]["book"], selector["lessons"]["numbers"]
        order = {n: i for i, n in enumerate(numbers)}

        def first_lesson(word):
            hits = [order[l["lesson"]] for l in word["lessons"] if l["book"] == book and l["lesson"] in order]
            return min(hits) if hits else None
        chosen = [(first_lesson(w), i, w) for i, w in enumerate(chosen)]
        chosen = [w for lesson, _, w in sorted(c for c in chosen if c[0] is not None)]
    return chosen


def pick_cards(words, spec, table, word_overrides=None):
    pool = candidates(words, spec["select"])
    size = spec["size"]
    if spec["select"].get("lessons"):
        # Keep the lesson's own order; drop grammar forms first, then fill if the lesson is short.
        base = [w for w in pool if not is_derived(w)]
        keep = {w["key"] for w in (base + [w for w in pool if is_derived(w)])[:size]}
        picked = [w for w in pool if w["key"] in keep]
    else:
        pool = [w for w in pool if not is_derived(w)]
        # Prefer words that carry a reviewed example, then a stable per-set shuffle.
        picked = sorted(pool, key=lambda w: (w["example_ja"] is None, number(spec["key"], "card", w["key"])))[:size]
        picked = ranked(picked, spec["key"], "order")
    if len(picked) < size:
        raise ValueError(f"{spec['key']}: selector yields {len(picked)} cards, needs {size}")
    return [{"set_key": spec["key"], "position": position, "front": w["expression"], "back": w["meaning"],
             "reading": w["reading"], "meaning": w["meaning"], "han_viet": han_viet(w["expression"], table, word_overrides),
             "jlpt_level": w["level"], "word_type": word_type(w), "example_ja": w["example_ja"],
             "example_vi": w["example_vi"], "source": w["source"]}
            for position, w in enumerate(picked)]


COUNT_IN_TEXT = re.compile(r"\d+\s*(từ|thẻ|ký tự|chữ)", re.IGNORECASE)


def check_text(key, title, description):
    if not 1 <= len(title) <= 100 or len(description or "") > 500:
        raise ValueError(f"{key}: title/description too long")
    # The library already shows the card count; titles/descriptions must not repeat (and go stale on) it.
    if COUNT_IN_TEXT.search(title) or COUNT_IN_TEXT.search(description or ""):
        raise ValueError(f"{key}: title/description must not state a word/card count")


def activity(key, owner, card_count, fronts, spec, keys, seen_comments):
    """Completed studies, a full 1-5 star histogram, favorites and rated comments for one set."""
    stars, favorites, completed = spec["stars"], spec["favorites"], spec["completed"]
    others = ranked([k for k in keys if k != owner], key, "learners")
    if len(stars) != 5 or min(stars) < 1:
        raise ValueError(f"{key}: every star level 1-5 needs at least one vote")
    if not sum(stars) <= completed <= len(others) or not 0 <= favorites <= completed:
        raise ValueError(f"{key}: votes/favorites must come from distinct learners who completed the set")
    completers = others[:completed]
    voters = ranked(completers, key, "voters")[:sum(stars)]
    values = ranked([star for star, count in enumerate(stars, 1) for _ in range(count)], key, "stars")
    values = [v for _, v in sorted(enumerate(values), key=lambda pair: number(key, "vote-order", pair[0]))]
    votes = dict(zip(voters, values))
    liked = set(ranked(completers, key, "favorites")[:favorites])
    states, sessions, comments, first_done = [], [], [], {}
    for user in completers:
        # Activity is placed as a per-mille of [set created, import time] so it never precedes the set.
        first = 30 + number(key, user, "first") % 600
        runs = 1 + number(key, user, "runs") % 3
        times = sorted({first} | {first + 1 + number(key, user, "run", n) % (990 - first) for n in range(1, runs)})
        for sequence, at in enumerate(times, 1):
            sessions.append({"set_key": key, "user_key": user, "seq": sequence, "permille": at,
                             "seconds": 120 + number(key, user, "seconds", sequence) % (8 * card_count + 240)})
        first_done[user] = first
        states.append({"set_key": key, "user_key": user, "rating": votes.get(user), "favorite": int(user in liked),
                       "first_permille": first, "last_permille": times[-1]})
    used = set()
    for comment in spec.get("comments", []):
        star = comment["stars"]
        eligible = [u for u in voters if u not in used and votes[u] == star]
        if not eligible:
            raise ValueError(f"{key}/{comment['key']}: no distinct voter with {star} stars")
        author = ranked(eligible, key, comment["key"], "author")[0]
        used.add(author)
        text = comment["text"].strip()
        if not 30 <= len(text) <= 600 or any(m in text for m in ("<", ">", "http://", "https://")):
            raise ValueError(f"{key}/{comment['key']}: comment must be meaningful plain text")
        if re.search(rf"(?<!\d){card_count}\s*(từ|thẻ)", text):
            raise ValueError(f"{key}/{comment['key']}: comment must not quote the set's card count")
        wish = set(comment.get("wish", []))
        # A word quoted in a review must really be on the set's cards (or be an explicit wish).
        for token in set(JAPANESE.findall(text)) - GRAMMAR_TERMS:
            if (token in wish) == (token in fronts):
                raise ValueError(f"{key}/{comment['key']}: comment mentions {token!r} "
                                 + ("which is already a card" if token in wish else "which is not a card"))
        if text in seen_comments:
            raise ValueError(f"{key}/{comment['key']}: comments must not repeat")
        seen_comments.add(text)
        at = first_done[author] + 5 + number(key, comment["key"], "at") % (995 - first_done[author] - 5)
        comments.append({"set_key": key, "key": comment["key"], "user_key": author, "content": text,
                         "rating": star, "permille": at})
    stats = {"rating_count": len(votes), "average_rating": round(sum(votes.values()) / len(votes), 2),
             "rating_distribution": stars, "favorites": len(liked), "study_count": len(sessions),
             "comments": len(comments)}
    return states, sessions, comments, stats


def legacy_han_viet(front, table, word_overrides):
    """Hán Việt for a legacy card front, skipping whole sentences such as 駅はどこですか."""
    if front.endswith(("ですか", "ますか", "ください", "。")) or "は" in front[1:-1]:
        return None
    return han_viet(front, table, word_overrides)


def build_community(pool=None, source=None, learners=None):
    pool = pool or json.loads((DATA / "vocab_pool.json").read_text(encoding="utf-8"))
    source = source or json.loads((DATA / "community_sets.json").read_text(encoding="utf-8"))
    learners = learners or load_learners()
    by_key = {l["key"]: l for l in learners}
    keys = [l["key"] for l in learners]
    specs = source["sets"]
    if len(specs) < 20:
        raise ValueError("At least 20 community sets are required")
    all_keys = [s["key"] for s in specs] + [s["key"] for s in source.get("legacy_sets", [])]
    if len(set(all_keys)) != len(all_keys):
        raise ValueError("Duplicate set key")
    sizes = sorted(s["size"] for s in specs)
    if sizes[0] < MIN_CARDS or len(set(sizes)) != len(sizes) or any(b - a < 2 for a, b in zip(sizes, sizes[1:])):
        raise ValueError("Set sizes must be >= 20, distinct and spread at least 2 apart")
    titles = [(s["owner"], s["title"]) for s in specs]
    if len(set(titles)) != len(titles):
        raise ValueError("An owner cannot have two sets with the same title")
    table = dict(pool["kanji_han_viet"], **source.get("han_viet_char_overrides", {}))
    word_overrides = source.get("han_viet_word_overrides", {})
    sets, cards, states, sessions, comments, summary = [], [], [], [], [], []
    seen_comments = set()
    for spec in specs:
        key, owner = spec["key"], spec["owner"]
        if owner not in by_key:
            raise ValueError(f"{key}: unknown owner {owner}")
        check_text(key, spec["title"], spec.get("description"))
        set_cards = pick_cards(pool["words"], spec, table, word_overrides)
        cards += set_cards
        st, se, co, stats = activity(key, owner, len(set_cards), {c["front"] for c in set_cards},
                                     spec, keys, seen_comments)
        states += st
        sessions += se
        comments += co
        sets.append({"key": key, "owner_key": owner, "title": spec["title"], "description": spec.get("description"),
                     "age_days": spec["age_days"]})
        summary.append({"key": key, "title": spec["title"], "owner": by_key[owner]["name"], "cards": len(set_cards),
                        "levels": sorted({c["jlpt_level"] for c in set_cards}),
                        "jmdict_cards": sum(c["source"] == "JMDICT" for c in set_cards),
                        "with_example": sum(c["example_ja"] is not None for c in set_cards),
                        "with_han_viet": sum(c["han_viet"] is not None for c in set_cards), **stats})
    for card in cards:
        card.pop("source")

    # Public sets of the older demo seeds: rename away card counts, fill Hán Việt, add learner activity.
    exported = {s["title"]: s for s in pool.get("legacy_sets", [])}
    legacy, legacy_titles, legacy_descriptions, hv = [], [], [], {}
    for spec in source.get("legacy_sets", []):
        key = spec["key"]
        check_text(key, spec["title"], spec.get("description"))
        known = [spec["title"], *spec.get("old_titles", [])]
        found = [exported[t] for t in known if t in exported]
        if not found:
            raise ValueError(f"{key}: none of {known} is in the exported legacy sets (re-run extract_pool.py)")
        set_cards = found[0]["cards"]
        for card in set_cards:
            value = None if card["han_viet"] else legacy_han_viet(card["front"], table, word_overrides)
            if value:
                if hv.get(card["front"], value) != value:
                    raise ValueError(f"{key}: conflicting Hán Việt for {card['front']}")
                hv[card["front"]] = value
        st, se, co, stats = activity(key, None, len(set_cards), {c["front"] for c in set_cards},
                                     spec, keys, seen_comments)
        states += st
        sessions += se
        comments += co
        legacy.append({"key": key, "title": spec["title"], "description": spec.get("description")})
        legacy_titles += [{"set_key": key, "title": t} for t in known]
        old = {found[0].get("description"), *spec.get("old_descriptions", [])} - {None, spec.get("description")}
        legacy_descriptions += [{"set_key": key, "description": d} for d in sorted(old)]
        summary.append({"key": key, "title": spec["title"], "owner": pool.get("legacy_owner"),
                        "cards": len(set_cards), "legacy": True,
                        "with_han_viet": sum(bool(c["han_viet"] or hv.get(c["front"])) for c in set_cards),
                        "kanji_cards": sum(bool(KANJI.search(c["front"])) for c in set_cards), **stats})
    used_learners = sorted({s["owner_key"] for s in sets} | {s["user_key"] for s in states})
    return {"version": source["version"], "provenance": source["provenance"],
            "learners": [by_key[k] for k in used_learners], "sets": sets, "cards": cards,
            "legacy_owner": pool.get("legacy_owner"), "legacy_sets": legacy, "legacy_titles": legacy_titles,
            "legacy_descriptions": legacy_descriptions,
            "legacy_han_viet": [{"front": f, "han_viet": v} for f, v in sorted(hv.items())],
            "states": states, "sessions": sessions, "comments": comments, "summary": summary}


def capacity_report(pool=None):
    pool = pool or json.loads((DATA / "vocab_pool.json").read_text(encoding="utf-8"))
    words = pool["words"]
    rows = {"katakana N5": {"katakana": True, "levels": ["N5"]}, "katakana all": {"katakana": True},
            "JMDICT N5": {"source": "JMDICT", "levels": ["N5"]}, "JMDICT N4": {"source": "JMDICT", "levels": ["N4"]},
            "verbs N5": {"verbs": True, "levels": ["N5"]}, "verbs N4": {"verbs": True, "levels": ["N4"]},
            "kanji N5": {"kanji": True, "levels": ["N5"]}, "kanji N4": {"kanji": True, "levels": ["N4"]}}
    for name, selector in rows.items():
        print(name, len([w for w in candidates(words, selector) if not is_derived(w)]))
    for book, numbers in (("Sekai N5", range(1, 26)), ("Sekai N4", range(26, 51))):
        print(book, {n: (len(c := candidates(words, {"lessons": {"book": book, "numbers": [n]}})),
                         sum(not is_derived(w) for w in c)) for n in numbers})


if __name__ == "__main__":
    if sys.argv[1:] == ["--capacity"]:
        capacity_report()
    else:
        data = build_community()
        print(json.dumps({k: len(v) for k, v in data.items() if isinstance(v, list)}, indent=2))
