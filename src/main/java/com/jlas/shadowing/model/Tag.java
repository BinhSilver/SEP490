package com.jlas.shadowing.model;

import jakarta.persistence.*;
import lombok.Data;

@Entity(name = "ShadowingTag")
@Table(name = "tag")
@Data
public class Tag {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "tag_type", nullable = false, length = 20)
    private TagType tagType = TagType.VIDEO;

    public enum TagType { VIDEO, KNOWLEDGE, SITUATION, SKILL }
}
