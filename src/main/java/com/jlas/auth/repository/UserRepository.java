package com.jlas.auth.repository;

import com.jlas.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Repository thao tác dữ liệu cho {@link User}.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /** Serializes credential linking and password-reset challenge issuance/consumption. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findForAuthentication(@Param("email") String email);

    Optional<User> findByUsername(String username);

    Optional<User> findByGoogleId(String googleId);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);

    /** Serializes avatar/profile changes and appeal creation for a user. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findForProfileUpdate(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :userId")
    Optional<User> findForCreditUpdate(@Param("userId") Long userId);

    /** Only the birth date, for Shadowing age ratings: video streaming asks often and needs nothing else. */
    @Query("select u.dateOfBirth from User u where u.id = :userId")
    Optional<LocalDate> findDateOfBirthById(@Param("userId") Long userId);
}
