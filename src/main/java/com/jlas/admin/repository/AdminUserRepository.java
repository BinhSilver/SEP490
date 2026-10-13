package com.jlas.admin.repository;

import com.jlas.auth.entity.Role;
import com.jlas.auth.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    // Serialize account lock/unlock decisions, including the last-admin check.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.name = 'SUPER_ADMIN'")
    Role lockAdminRole();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findForUpdate(@Param("id") Long id);

    long countByRole_NameAndLockedAtIsNull(String role);
}
