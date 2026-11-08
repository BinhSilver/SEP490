package com.jlas.admin.entity;

import com.jlas.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;

/**
 * [JLAS2-51] Runtime override of one {@link com.jlas.admin.enums.SystemConfigKey}. A null value means the
 * override was reset to the default; the row is kept so {@link #version} keeps counting and an edit based on a
 * value read before the reset is still rejected.
 */
@Entity
@Table(name = "system_config")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemConfig {
    @Id
    @Column(name = "config_key", length = 80)
    private String configKey;
    @Column(name = "config_value", length = 1000)
    private String configValue;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by", foreignKey = @ForeignKey(name = "fk_system_config_updated_by"))
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User updatedBy;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    @Version
    @Column(nullable = false)
    private Long version;

    public SystemConfig(String configKey) {
        this.configKey = configKey;
    }
}
