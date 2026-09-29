package com.jlas.learning.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * [JLAS-W0108] Strip code-fence + repair JSON (dấu phẩy thừa) + parse — dùng chung cho mọi
 * provider AI (Gemini/DeepSeek) để đầu ra JSON được chuẩn hoá giống nhau trước khi trả về caller.
 */
final class JsonContentParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private JsonContentParser() {
    }

    /** Lấy JSON từ nội dung model trả về: bỏ code-fence, thử parse trực tiếp rồi rút object/array. */
    static JsonNode extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
        }
        JsonNode direct = tryParse(trimmed);
        if (direct != null) {
            return direct;
        }
        int objectStart = trimmed.indexOf('{');
        int arrayStart = trimmed.indexOf('[');
        int start;
        char close;
        if (objectStart >= 0 && (arrayStart < 0 || objectStart < arrayStart)) {
            start = objectStart;
            close = '}';
        } else if (arrayStart >= 0) {
            start = arrayStart;
            close = ']';
        } else {
            return null;
        }
        int end = trimmed.lastIndexOf(close);
        if (end <= start) {
            return null;
        }
        return tryParse(trimmed.substring(start, end + 1));
    }

    static JsonNode tryParse(String candidate) {
        for (String value : List.of(candidate, repairJson(candidate))) {
            if (value == null) {
                continue;
            }
            try {
                JsonNode node = OBJECT_MAPPER.readTree(value);
                if (node != null && (node.isObject() || node.isArray())) {
                    return node;
                }
            } catch (Exception ignored) {
                // thử ứng viên tiếp theo
            }
        }
        return null;
    }

    /** Bỏ dấu phẩy thừa trước } / ] mà model nhỏ hay sinh ra. */
    static String repairJson(String json) {
        if (json == null) {
            return null;
        }
        return json.replaceAll(",\\s*}", "}").replaceAll(",\\s*]", "]");
    }
}
