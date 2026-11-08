package com.jlas.learning.service.impl;

import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * [JLAS-W0108] OD-D2-05 read side of {@link CanonicalFirstOccurrence}: the chapter's own words (or Kanji) that are not
 * required here because an earlier activity of the same path already taught them, each with where it was learnt.
 * Paths made before the dedupe require every word, so they get an empty list. Shown on the chapter's first part only.
 */
final class LessonReviewItems {
    record Review(Long canonicalId, String learnedIn, boolean known) {
        Review(Long canonicalId, String learnedIn) { this(canonicalId, learnedIn, false); }
    }

    private LessonReviewItems() { }

    static List<Review> forActivity(UserLearningPathItem activity, LearningActivityType type, List<Long> lessonIds,
            UserLearningPathItemRepository items, LearningActivityMemberRepository members) {
        return forActivity(activity, type, lessonIds, items, members, null);
    }

    /** {@code extendedWord} (may be null) tells an EXTENDED word apart from a known one on a path that has both. */
    static List<Review> forActivity(UserLearningPathItem activity, LearningActivityType type, List<Long> lessonIds,
            UserLearningPathItemRepository items, LearningActivityMemberRepository members,
            java.util.function.Predicate<Long> extendedWord) {
        if (activity == null || activity.getLesson() == null || activity.getLearningPath() == null || lessonIds.isEmpty()
                || (activity.getPartIndex() != null && activity.getPartIndex() > 1)) return List.of();
        Long lessonId = activity.getLesson().getId();
        List<UserLearningPathItem> sameType = items.findByLearningPathIdOrderByOrderIndex(activity.getLearningPath().getId())
                .stream().filter(item -> item.getActivityType() == type && item.getId() != null)
                .sorted(Comparator.comparing(UserLearningPathItem::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (sameType.isEmpty()) return List.of();
        Map<Long, UserLearningPathItem> byId = new LinkedHashMap<>();
        sameType.forEach(item -> byId.put(item.getId(), item));
        Set<Long> requiredHere = new HashSet<>();
        Map<Long, UserLearningPathItem> firstTaught = new LinkedHashMap<>();
        for (LearningActivityMember member : members.findByActivityIdsInOrder(byId.keySet())) {
            UserLearningPathItem owner = byId.get(member.getActivity().getId());
            if (owner == null) continue;
            if (owner.getLesson() != null && Objects.equals(owner.getLesson().getId(), lessonId)) requiredHere.add(member.getCanonicalId());
            UserLearningPathItem previous = firstTaught.get(member.getCanonicalId());
            if (previous == null || order(owner) < order(previous)) firstTaught.put(member.getCanonicalId(), owner);
        }
        // F1-3 (owner 2026-10-27): a path squeezed by its exam date (policy ":X<days>") leaves EXTENDED words out of the
        // required part; they are listed too, with no "learnt in" (the page shows them as "Mở rộng — không bắt buộc").
        String policy = String.valueOf(activity.getLearningPath().getPolicyVersion());
        boolean squeezed = policy.contains(":X");
        // F1-4 (owner 2026-10-28): a path made with known units (policy ":KN") leaves them out of the required part too;
        // they are listed as known ("Đã vững"). On a path that is also squeezed, an EXTENDED word stays "Mở rộng".
        boolean withKnown = policy.contains(":KN");
        List<Review> reviews = new ArrayList<>();
        List<Review> known = new ArrayList<>();
        List<Review> extended = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(lessonIds)) {
            UserLearningPathItem taught = firstTaught.get(id);
            if (requiredHere.contains(id)) continue;
            if (taught != null && order(taught) < order(activity)) reviews.add(new Review(id, title(taught)));
            else if (squeezed && (!withKnown || extendedWord == null || extendedWord.test(id))) extended.add(new Review(id, null));
            else if (withKnown) known.add(new Review(id, null, true));
        }
        reviews.addAll(known);
        reviews.addAll(extended);
        return List.copyOf(reviews);
    }

    private static int order(UserLearningPathItem item) {
        return item.getOrderIndex() == null ? Integer.MAX_VALUE : item.getOrderIndex();
    }

    /** "Giới thiệu bản thân" — the lesson title without the textbook's "Bài 17:" prefix, as the roadmap shows it. */
    private static String title(UserLearningPathItem item) {
        String raw = item.getLesson() == null || item.getLesson().getTitle() == null ? "" : item.getLesson().getTitle();
        String stripped = raw.replaceFirst("^\\s*Bài\\s*\\d+\\s*[:：.\\-–—]\\s*", "").trim();
        return stripped.isEmpty() ? raw.trim() : stripped;
    }
}
