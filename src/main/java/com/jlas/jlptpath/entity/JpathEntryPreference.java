package com.jlas.jlptpath.entity;

import com.jlas.jlptpath.enums.JpathKanaChoice;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/** [JLAS-W0108] Answers given when entering the path (jpath_entry_preference). Day lists are CSV such as "WED,SAT". */
@Entity
@Table(name = "jpath_entry_preference")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class JpathEntryPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "studied_before", nullable = false)
    private boolean studiedBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "kana_choice", nullable = false, length = 10)
    private JpathKanaChoice kanaChoice;

    @Builder.Default
    @Column(name = "skill_days", nullable = false, length = 16)
    private String skillDays = "WED,SAT";

    @Builder.Default
    @Column(name = "rest_days", nullable = false, length = 16)
    private String restDays = "";

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
