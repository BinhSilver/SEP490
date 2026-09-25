package com.jlas.shadowing.model;

import lombok.*;
import java.io.Serializable;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
public class VideoUserStateId implements Serializable {
    private Long userId;
    private Long videoId;
}
