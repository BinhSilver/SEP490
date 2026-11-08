"""Sinh seed hoạt động MÔ PHỎNG (lượt học, số sao, yêu thích) cho các bộ flashcard giáo trình — chỉ dùng dev/demo.

Người học là 100 học viên mẫu hư cấu của tools/demo_learners.py (đã có trong DB từ seed Shadowing). Mỗi bộ có một
nhóm học viên chọn tất định theo mã bộ; bộ cấp thấp và bài đầu sách được học nhiều hơn, số sao lệch về 4–5.
Mốc thời gian tính khi chạy: sau ngày tạo tài khoản học viên và sau ngày tạo bộ, trước lúc chạy một giờ.

Chạy: python tools/flashcard_textbook/build_activity.py   (sau build_seed.py)
Ra:   sql/seeds/seed_data_flashcard_textbook_activity.sql (thêm bản giống hệt ở sqlrun/40_flashcard_textbook_activity.sql
      nếu có sqlrun/)
"""
from __future__ import annotations

import collections
import hashlib
import json
import random
import sys
from pathlib import Path

from build_seed import DATA, ROOT, Source, inserts, textbook_sets
from fill_words import FILL, KANJI_FILL
from han_viet import kanji_candidates, read_cache, read_json

sys.path.insert(0, str(ROOT / "tools"))
from demo_learners import build_learners  # noqa: E402

TEMPLATE = Path(__file__).with_name("activity_template.sql")
OUTPUT = ROOT / "sql/seeds/seed_data_flashcard_textbook_activity.sql"
RUN_OUTPUT = ROOT / "sqlrun/40_flashcard_textbook_activity.sql"
# Số học viên trung bình mỗi bộ theo cấp; bài sau trong sách ít người học hơn.
POPULARITY = {"KANA": 60, "N5": 34, "N4": 26, "N3": 18, "N2": 13, "N1": 9}
RATINGS = (5, 4, 3, 2, 1)
RATING_WEIGHTS = (48, 36, 12, 3, 1)


def rng(*parts) -> random.Random:
    return random.Random(int(hashlib.sha256("|".join(map(str, parts)).encode()).hexdigest(), 16))


def activity(sets: list[dict], emails: list[str]) -> tuple[list[tuple], list[tuple]]:
    states, sessions = [], []
    lesson_index: dict[str, int] = {}
    for item in sets:
        level = "KANA" if item["kind"] == "KANA" else item["level"]
        index = lesson_index[item.get("book", "KANA")] = lesson_index.get(item.get("book", "KANA"), -1) + 1
        r = rng("set", item["key"])
        count = round(POPULARITY[level] / (1 + index / 20) * r.uniform(0.6, 1.4))
        learners = sorted(emails, key=lambda e: rng("pick", item["key"], e).random())[:max(3, min(count, len(emails)))]
        for email in learners:
            p = rng("learner", item["key"], email)
            first, last = sorted((p.randint(0, 1000), p.randint(0, 1000)))
            rating = p.choices(RATINGS, RATING_WEIGHTS)[0] if p.random() < 0.55 else None
            states.append((item["key"], email, rating, int(p.random() < 0.22), first, last))
            for seq in range(p.choices((1, 2, 3), (60, 28, 12))[0]):
                moment = first if seq == 0 else p.randint(first, last)
                request = "seed-fct-" + hashlib.sha1(f"{item['key']}|{email}|{seq}".encode()).hexdigest()[:27]
                sessions.append((item["key"], email, request, moment, p.randint(90, 900)))
    return states, sessions


def build() -> dict:
    kana = json.loads((DATA / "kana_sets.json").read_text(encoding="utf-8"))
    caches = {"han_viet": read_cache(), "words": read_json(FILL), "kanji": read_json(KANJI_FILL), "table": kanji_candidates()}
    sets = [dict(s, key=f"KANA-{i}", kind="KANA") for i, s in enumerate(kana, 1)] + \
        textbook_sets(Source(), caches, collections.Counter())
    names = json.loads((ROOT / "data/shadowing/demo_engagement.json").read_text(encoding="utf-8"))["learners"]
    emails = [learner["email"] for learner in build_learners(names)]
    states, sessions = activity(sets, emails)
    data = "\n".join(
        inserts("fca_set", ("set_key", "kind", "title"), [(s["key"], s["kind"], s["title"]) for s in sets])
        + inserts("fca_learner", ("email",), [(e,) for e in emails])
        + inserts("fca_state", ("set_key", "email", "rating", "favorite", "first_permille", "last_permille"), states)
        + inserts("fca_session", ("set_key", "email", "request_id", "permille", "seconds"), sessions))
    totals = {"SET_COUNT": len(sets), "LEARNER_COUNT": len(emails), "STATE_COUNT": len(states),
              "RATING_COUNT": sum(s[2] is not None for s in states), "FAVORITE_COUNT": sum(s[3] for s in states),
              "SESSION_COUNT": len(sessions)}
    sql = TEMPLATE.read_text(encoding="utf-8").replace("{{DATA}}", data)
    for token, count in totals.items():
        sql = sql.replace("{{" + token + "}}", str(count))
    OUTPUT.write_bytes(sql.encode("utf-8"))
    if RUN_OUTPUT.parent.is_dir():  # sqlrun/ chỉ có trên máy giữ file chạy DB local
        RUN_OUTPUT.write_bytes(sql.encode("utf-8"))
    return totals


if __name__ == "__main__":
    print(json.dumps(build(), indent=2))
