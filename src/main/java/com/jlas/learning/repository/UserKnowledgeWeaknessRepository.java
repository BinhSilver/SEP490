package com.jlas.learning.repository;

import com.jlas.learning.entity.UserKnowledgeWeakness;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * [JLAS-W0108] Lát 2 — kho cho Sổ tay điểm yếu (Weakness Notebook).
 *
 * <p>Notebook khớp natural key (user, tag, canonical_item, jlpt_level, topic, outcome) trong bộ nhớ
 * trên tập dòng của một user thay vì native upsert, để chạy được cả trên H2 (test) lẫn MySQL.</p>
 */
public interface UserKnowledgeWeaknessRepository extends JpaRepository<UserKnowledgeWeakness, Long> {

    List<UserKnowledgeWeakness> findByUserId(Long userId);

    /** [JLAS-W0122] Điểm yếu chưa đạt mastery (dùng cho remediation khi replan). */
    List<UserKnowledgeWeakness> findByUserIdAndStatusNot(Long userId, String status);
}
