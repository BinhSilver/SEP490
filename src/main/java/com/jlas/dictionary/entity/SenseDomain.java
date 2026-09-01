package com.jlas.dictionary.entity;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

/**
 * Gắn từng nghĩa vào một hoặc nhiều chuyên ngành.
 * Table: sense_domain
 */
@Entity
@Table(name = "sense_domain")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SenseDomain {

    @EmbeddedId
    private SenseDomainId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("senseId")
    @JoinColumn(name = "sense_id",
            foreignKey = @ForeignKey(name = "fk_sense_domain_sense"))
    private VocabularySense sense;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("domainId")
    @JoinColumn(name = "domain_id",
            foreignKey = @ForeignKey(name = "fk_sense_domain_domain"))
    private DictionaryDomain domain;

    // -----------------------------------------------------------------------

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class SenseDomainId implements Serializable {
        @Column(name = "sense_id")
        private Long senseId;

        @Column(name = "domain_id")
        private Long domainId;
    }
}
