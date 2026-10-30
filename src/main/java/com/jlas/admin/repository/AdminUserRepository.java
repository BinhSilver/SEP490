package com.jlas.admin.repository;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    // Serialize account lock/unlock decisions, including the last-admin check.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.name = 'ADMIN'")
    Role lockAdminRole();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findForUpdate(@Param("id") Long id);

    long countByRole_NameAndLockedAtIsNull(String role);

    // ── Dashboard ──────────────────────────────────────────────────────────
    @Query("select r.name, count(u) from User u join u.role r group by r.name")
    List<Object[]> countByRoleName();

    long countByLockedAtIsNotNull();

    long countByIsVerifiedFalse();

    long countByCreatedAtGreaterThanEqual(LocalDateTime from);

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime from, LocalDateTime to);

    /** [year, accounts created that year] for accounts created on or after {@code from}. */
    @Query("select year(u.createdAt), count(u) from User u where u.createdAt >= :from group by year(u.createdAt)")
    List<Object[]> countCreatedByYearSince(@Param("from") LocalDateTime from);

    /** Same Premium rule as everywhere else: a non-FREE plan that has not expired yet. */
    @Query("select count(u) from User u where u.premiumPlan is not null"
            + " and upper(u.premiumPlan.name) <> 'FREE' and u.premiumExpiredAt > :now")
    long countActivePremium(@Param("now") LocalDateTime now);

    /** [planId, accounts whose Premium on that plan is still running]. */
    @Query("select u.premiumPlan.id, count(u) from User u where u.premiumPlan is not null"
            + " and u.premiumExpiredAt > :now group by u.premiumPlan.id")
    List<Object[]> countRunningByPlan(@Param("now") LocalDateTime now);
}
