package com.jlas.flashcard.dto;

import lombok.*;
import java.util.List;

public class AiDtos {

    @Data
    public static class AiLookupRequest {
        private Long   cardId;
        private String term;
        private String type;    // auto | vocabulary | grammar | explain | examples | kanji | chat
        private String context;
        private String message; // for chat follow-up
        private String history; // optional prior conversation summary
    }

    @Data @Builder
    public static class AiLookupResponse {
        private String type;
        private String term;
        private String reading;
        private String meaning;
        private String hanViet;
        private String jlptLevel;
        private String grammarPoint;
        private String usage;
        private String tip;
        private List<KanjiPartDto> kanjiParts;
        private List<ExampleDto> examples;
        private List<ExampleGroupDto> exampleGroups;
        private String rawMarkdown;
        private String reply;
        private String error;
        private boolean mock;
    }

    @Data @Builder
    public static class ExampleDto {
        private String japanese;
        private String reading;
        private String vietnamese;
        private String explanation;
        private String level;
    }

    @Data @Builder
    public static class ExampleGroupDto {
        private String level; // N5 | N4 | N3
        private List<ExampleDto> examples;
    }

    @Data @Builder
    public static class KanjiPartDto {
        private String character;
        private String onyomi;
        private String kunyomi;
        private String hanViet;
        private String meaning;
    }

    @Data
    public static class AiChatRequest {
        private Long   cardId;
        private String term;
        private String message;
        private String context;
    }
}
