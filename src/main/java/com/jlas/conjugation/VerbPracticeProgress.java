package com.jlas.conjugation;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity @Table(name="verb_practice_progress", uniqueConstraints=@UniqueConstraint(name="uk_verb_progress",columnNames={"user_id","verb_id","form_code"}))
@Getter @Setter @NoArgsConstructor
public class VerbPracticeProgress {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(name="verb_id",nullable=false,length=40) private String verbId;
    @Column(name="form_code",nullable=false,length=30) private String formCode;
    @Column(nullable=false) private int attempts;
    @Column(name="correct_count",nullable=false) private int correctCount;
    @Column(name="needs_review",nullable=false) private boolean needsReview;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
}
