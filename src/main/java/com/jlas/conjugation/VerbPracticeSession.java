package com.jlas.conjugation;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.*;

@Entity @Table(name="verb_practice_session", indexes=@Index(name="idx_verb_session_user_started",columnList="user_id,started_at"))
@Getter @Setter @NoArgsConstructor
public class VerbPracticeSession {
    @Id @Column(length=36) private String id;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(nullable=false,length=20) private String mode;
    @Lob @Column(nullable=false,columnDefinition="LONGTEXT") private String payload;
    @Column(name="answered_count",nullable=false) private int answeredCount;
    @Column(name="correct_count",nullable=false) private int correctCount;
    @Column(name="started_at",nullable=false) private Instant startedAt;
    @Column(name="deadline_at") private Instant deadlineAt;
    @Column(name="finished_at") private Instant finishedAt;
}
