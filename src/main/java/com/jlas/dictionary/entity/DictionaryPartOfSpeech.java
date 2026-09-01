package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * Danh mục từ loại dùng chung cho các nghĩa từ điển.
 * Table: dictionary_part_of_speech
 */
@Entity
@Table(name = "dictionary_part_of_speech")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DictionaryPartOfSpeech {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    @Column(name = "name_vi", nullable = false, length = 150)
    private String nameVi;

    @Column(name = "name_en", length = 150)
    private String nameEn;

    @Column(name = "name_ja", length = 150)
    private String nameJa;

    @Column(name = "description", length = 500)
    private String description;
}
