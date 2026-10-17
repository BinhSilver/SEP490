package com.jlas.learning.service.impl;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.enums.LearningMemberType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Pure planning only: no history, membership, or managed entity is mutated by a preview. */
final class PaceActivityPartitioner {
    record Part(List<LearningActivityMember> members, int minutes) { }

    static List<Part> partition(UserLearningPathItem activity, List<LearningActivityMember> members, int budget) {
        Integer minutes = activity.getEstimatedMinutes();
        if (minutes == null || minutes <= 0) throw blocked();
        if (minutes <= budget) return List.of(new Part(List.of(), minutes));
        if (activity.getActivityType() == null || members.isEmpty()) throw blocked();
        LearningMemberType expected = switch (activity.getActivityType()) {
            case VOCABULARY -> LearningMemberType.VOCABULARY_ENTRY;
            case KANJI -> LearningMemberType.KANJI;
            case FOUNDATION -> LearningMemberType.FOUNDATION_CHARACTER;
            case GRAMMAR -> LearningMemberType.GRAMMAR_POINT;
            // A dialogue or passage cannot safely be cut at an arbitrary row boundary.
            case DIALOGUE, READING, LISTENING -> throw blocked();
        };
        var seen = new HashSet<Long>();
        for (var member : members) {
            if (member.getMemberType() != expected || member.getCanonicalId() == null
                    || !seen.add(member.getCanonicalId()) || member.getSourceRevision() == null
                    || member.getSourceRevision().isBlank()) throw blocked();
        }
        var policy = new LearningWorkloadPolicy();
        int maximum = Math.min(15, members.size());
        while (maximum > 0 && estimate(activity, maximum, members.size(), policy) > budget) maximum--;
        if (maximum == 0) throw blocked();
        int count = (members.size() + maximum - 1) / maximum;
        // The five-item threshold belongs only to Smart Test remediation, not curriculum lessons.
        // Every scored part still needs at least two real members for a choice quiz.
        int minimum = 2;
        if (members.size() / count < minimum) throw blocked();
        List<Part> parts = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < count; i++) {
            int size = members.size() / count + (i < members.size() % count ? 1 : 0);
            parts.add(new Part(List.copyOf(members.subList(offset, offset + size)),
                    estimate(activity, size, members.size(), policy)));
            offset += size;
        }
        return List.copyOf(parts);
    }

    private static int estimate(UserLearningPathItem activity, int size, int total, LearningWorkloadPolicy policy) {
        // Never shrink a legacy estimate merely to make the new budget pass.
        int proportional = Math.toIntExact(((long) activity.getEstimatedMinutes() * size + total - 1) / total);
        return Math.max(proportional, policy.estimateMinutes(activity.getActivityType(), size));
    }

    private static ApiException blocked() {
        return new ApiException(ErrorCode.BLOCKED_CONTENT,
                "Một phần học chưa thể chia nhỏ theo nhịp này. Hãy giữ nhịp hiện tại hoặc chọn mức cao hơn.");
    }
}
