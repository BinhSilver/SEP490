package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Outcome (kỹ năng con) của câu hỏi Reading/Listening, map từ context_json.format.
 * Table: outcome
 *
 * <p>[JLAS-W0108] Domain mới trong Data-model C: outcome là cầu nối identity yếu
 * (skill + outcome + topic + level) sang tài nguyên học trong resource_mapping.</p>
 */
@Entity
@Table(name = "outcome")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Outcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Business key ổn định, ví dụ READ_MAIN_IDEA / LISTEN_TASK_COMPREHENSION. */
    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    @Column(name = "name_vi", nullable = false, length = 255)
    private String nameVi;

    @Column(name = "name_ja", length = 255)
    private String nameJa;

    /** READING/LISTENING. */
    @Column(name = "skill", nullable = false, length = 20)
    private String skill;
}
