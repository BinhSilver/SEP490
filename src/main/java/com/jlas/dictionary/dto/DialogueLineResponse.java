package com.jlas.dictionary.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/** Một lời thoại (content_item LISTENING) kèm vai, vị trí bong bóng, cụm tách nghĩa và audio. */
@Data
@Builder
public class DialogueLineResponse {
    private Long contentItemId;
    private Long sectionId;
    private String sectionTitle;
    private Integer orderIndex;
    private String text;
    private String translatedText;
    private Long roleId;
    private String roleCode;
    private String roleNameVi;
    private String sceneCode;
    private String bubbleSide;
    private Short bubbleX;
    private Short bubbleY;
    private List<DialogueChunkResponse> chunks;
    private List<ContentAudioResponse> audios;
}
