package com.jlas.moderation.repository;

import com.jlas.moderation.entity.UserSanction;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSanctionRepository extends JpaRepository<UserSanction, Long> {
    @Query("""
            select s from UserSanction s
            where s.user.id = :userId and s.revokedAt is null
              and (s.endsAt is null or s.endsAt > :now)
            order by s.createdAt desc
            """)
    List<UserSanction> findActiveByUserId(@Param("userId") Long userId,
                                          @Param("now") LocalDateTime now);

    @Query("""
            select s from UserSanction s
            where s.user.id = :userId and s.revokedAt is null
              and (s.endsAt is null or s.endsAt > :now)
            order by s.createdAt desc
            limit 1
            """)
    Optional<UserSanction> findLatestActiveByUserId(@Param("userId") Long userId,
                                                     @Param("now") LocalDateTime now);

    @Query("""
            select s from UserSanction s
            where s.user.id = :userId and s.sanctionType = :type
              and s.revokedAt is null and (s.endsAt is null or s.endsAt > :now)
            order by s.createdAt desc
            limit 1
            """)
    Optional<UserSanction> findActiveByUserIdAndType(@Param("userId") Long userId,
                                                      @Param("type") String type,
                                                      @Param("now") LocalDateTime now);
}
