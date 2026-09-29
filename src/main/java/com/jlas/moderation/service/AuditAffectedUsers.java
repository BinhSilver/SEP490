package com.jlas.moderation.service;

import com.jlas.auth.entity.User;
import com.jlas.auth.repository.UserRepository;
import com.jlas.moderation.entity.ModerationAuditLog;
import com.jlas.moderation.entity.ReviewCase;
import com.jlas.moderation.entity.UserSanction;
import com.jlas.moderation.repository.ReviewCaseRepository;
import com.jlas.moderation.repository.UserSanctionRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Finds the user each audit row is about (the content owner, appellant or sanctioned user),
 * so the audit trail shows who was affected and not only who acted. Batched: at most one
 * query per source for a whole page of rows.
 */
@Component
@RequiredArgsConstructor
public class AuditAffectedUsers {
    private final UserRepository users;
    private final UserSanctionRepository sanctions;
    private final ReviewCaseRepository reviewCases;

    public record Person(Long id, String name, String email) {
    }

    /** Audit row id → affected user; rows with no affected user (denylist, deleted users) are absent. */
    public Map<Long, Person> forRows(List<ModerationAuditLog> rows) {
        Map<Long, User> byUserId = users.findAllById(ids(rows, row -> "USER".equals(row.getTargetType()),
                ModerationAuditLog::getTargetId)).stream().collect(Collectors.toMap(User::getId, u -> u));
        Map<Long, User> bySanction = sanctions.findAllById(ids(rows, row -> "USER_SANCTION".equals(row.getTargetType()),
                ModerationAuditLog::getTargetId)).stream().collect(Collectors.toMap(UserSanction::getId, UserSanction::getUser));
        Map<Long, User> byCase = new HashMap<>();
        for (ReviewCase c : reviewCases.findAllById(ids(rows, row -> row.getCaseId() != null, ModerationAuditLog::getCaseId))) {
            User who = "APPEAL".equals(c.getCaseType()) ? c.getRequesterUser() : c.getOwnerUser();
            if (who != null) byCase.put(c.getId(), who);
        }

        Map<Long, Person> result = new HashMap<>();
        for (ModerationAuditLog row : rows) {
            User who = switch (row.getTargetType() == null ? "" : row.getTargetType()) {
                case "USER" -> byUserId.get(row.getTargetId());
                case "USER_SANCTION" -> bySanction.get(row.getTargetId());
                case "DENYLIST_TERM" -> null;
                default -> row.getCaseId() == null ? null : byCase.get(row.getCaseId());
            };
            if (who != null) result.put(row.getId(), new Person(who.getId(), who.getFullName(), who.getEmail()));
        }
        return result;
    }

    private static Set<Long> ids(List<ModerationAuditLog> rows, Predicate<ModerationAuditLog> filter,
                                 Function<ModerationAuditLog, Long> id) {
        return rows.stream().filter(filter).map(id).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
    }
}
