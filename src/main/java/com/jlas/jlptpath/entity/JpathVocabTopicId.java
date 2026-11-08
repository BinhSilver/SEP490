package com.jlas.jlptpath.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** [JLAS-W0108] Primary key of {@link JpathVocabTopic}. */
@Data @NoArgsConstructor @AllArgsConstructor
public class JpathVocabTopicId implements Serializable {
    private String level;
    private String topicKey;
}
