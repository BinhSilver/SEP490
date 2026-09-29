package com.jlas.moderation.repository;

import com.jlas.moderation.entity.ModerationPolicy;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModerationPolicyRepository extends JpaRepository<ModerationPolicy, Long> {
    List<ModerationPolicy> findByPolicyTypeOrderByPolicyKeyAsc(String policyType);
    List<ModerationPolicy> findByPolicyTypeAndIsActiveTrue(String policyType);
    Optional<ModerationPolicy> findByPolicyTypeAndPolicyKeyAndLanguage(String policyType, String policyKey,
                                                                      String language);
}
