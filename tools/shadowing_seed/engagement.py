"""Deterministic, explicitly fictional Shadowing engagement fixtures (no network/AI)."""
from __future__ import annotations

from hashlib import sha256
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from demo_learners import build_learners  # noqa: E402  (shared with tools/flashcard_seed)

DATA = Path(__file__).resolve().parents[2] / "data/shadowing"


def number(*parts):
    return int(sha256("|".join(map(str, parts)).encode("utf-8")).hexdigest(), 16)


def ranked(values, *salt):
    return sorted(values, key=lambda value: (number(*salt, value), str(value)))


def build_engagement(catalog, source=None):
    source = source or json.loads((DATA / "demo_engagement.json").read_text(encoding="utf-8"))
    learners = build_learners(source["learners"])
    videos = {v["source_key"]: v for v in catalog["videos"]}
    profiles = source["videos"]
    if len(profiles) != len(videos) or {p["source_key"] for p in profiles} != set(videos):
        raise ValueError("Every catalog source must have exactly one engagement profile")
    keys = [p["key"] for p in learners]
    events, states, comments, summary = [], [], [], []
    for profile in profiles:
        source_key = profile["source_key"]
        views, recent = profile["views"], profile["recent_views"]
        distribution = profile["stars"]
        if not (0 <= recent <= views and len(distribution) == 5
                and all(type(n) is int and n >= 0 for n in distribution)):
            raise ValueError(f"Invalid views/rating distribution: {source_key}")
        active = ranked(keys, source_key, "viewers")[:min(len(keys), views - recent)]
        if sum(distribution) > len(active) or not 0 <= profile["favorites"] <= len(active):
            raise ValueError(f"Votes/favorites exceed distinct viewers: {source_key}")
        # Every participating learner has an old view before any seeded comment.
        mandatory = {(user, 60 + number(source_key, user, "first-day") % 30) for user in active}
        old_pool = [(user, day) for user in active for day in range(7, 90) if (user, day) not in mandatory]
        recent_pool = [(user, day) for user in active for day in range(7)]
        extra = views - recent - len(mandatory)
        if extra > len(old_pool) or recent > len(recent_pool):
            raise ValueError(f"Too many views for the 24-hour viewer cooldown: {source_key}")
        selected = sorted(mandatory) + ranked(old_pool, source_key, "old-views")[:extra]
        selected += ranked(recent_pool, source_key, "recent-views")[:recent]
        latest = {}
        for user, day in sorted(selected):
            # Fixed minute for each viewer/video keeps repeated accepted views >=24h apart.
            age = day * 1440 + 60 + number(source_key, user, "minute") % 1260
            events.append({"source_key": source_key, "user_key": user, "minutes_ago": age})
            latest[user] = min(latest.get(user, age), age)
        voter_keys = ranked(active, source_key, "voters")[:sum(distribution)]
        ratings = ranked([star for star, count in enumerate(distribution, 1) for _ in range(count)], source_key, "stars")
        # Rank occurrences separately so equal ratings do not collapse or form large ordered blocks.
        ratings = [value for _, value in sorted(enumerate(ratings), key=lambda pair: number(source_key, "vote-order", pair[0]))]
        votes = dict(zip(voter_keys, ratings))
        favorites = set(ranked(active, source_key, "favorites")[:profile["favorites"]])
        duration = float(videos[source_key]["duration_seconds"])
        for user in active:
            position = duration if duration < 30 else duration * (40 + number(source_key, user, "progress") % 61) / 100
            states.append({"source_key": source_key, "user_key": user, "rating": votes.get(user),
                           "favorite": int(user in favorites), "position": round(position, 3),
                           "last_view_minutes_ago": latest[user]})
        used_authors = set()
        for comment in profile["comments"]:
            star = comment.get("stars")
            eligible = [user for user in voter_keys if user not in used_authors and (star is None or votes[user] == star)]
            if not eligible:
                raise ValueError(f"No distinct author with the comment's rating: {source_key}/{comment['key']}")
            author = ranked(eligible, source_key, comment["key"], "author")[0]
            used_authors.add(author)
            text = comment["text"].strip()
            if not 30 <= len(text) <= 1000 or any(mark in text for mark in ("<", ">", "http://", "https://")):
                raise ValueError(f"Comment must be meaningful plain text: {source_key}/{comment['key']}")
            comments.append({"source_key": source_key, "key": comment["key"], "user_key": author,
                             "content": text, "rating": star,
                             "minutes_ago": (10 + number(source_key, comment["key"], "day") % 45) * 1440
                                            + number(source_key, comment["key"], "time") % 1440})
        summary.append({"source_key": source_key, "title": videos[source_key]["title"], "views": views,
                        "views_last_7_days": recent, "rating_count": len(votes),
                        # A new source may have no demo activity yet: no rating rather than a made-up one.
                        "average_rating": round(sum(votes.values()) / len(votes), 4) if votes else None,
                        "rating_distribution": distribution, "favorites": len(favorites),
                        "comments": len(profile["comments"])})
    identities = [(e["source_key"], e["user_key"], e["minutes_ago"]) for e in events]
    comment_keys = [(c["source_key"], c["key"]) for c in comments]
    comment_times = [(c["source_key"], c["user_key"], c["minutes_ago"]) for c in comments]
    if len(identities) != len(set(identities)) or len(comment_keys) != len(set(comment_keys)) or len(comment_times) != len(set(comment_times)):
        raise ValueError("Duplicate engagement business key")
    if len({c["content"] for c in comments}) != len(comments):
        raise ValueError("Comments must not repeat across sources")
    return {"version": source["version"], "provenance": source["provenance"], "learners": learners,
            "videos": [{"source_key": key, "source_url": video["source_url"]} for key, video in videos.items()],
            "events": events, "states": states, "comments": comments, "summary": summary}


if __name__ == "__main__":
    catalog = json.loads((DATA / "catalog.json").read_text(encoding="utf-8"))
    data = build_engagement(catalog)
    print(json.dumps({"learners": len(data["learners"]), "videos": len(data["videos"]),
                      "views": len(data["events"]), "votes": sum(s["rating"] is not None for s in data["states"]),
                      "favorites": sum(s["favorite"] for s in data["states"]), "comments": len(data["comments"]),
                      "rated_comments": sum(c["rating"] is not None for c in data["comments"])}, indent=2))
