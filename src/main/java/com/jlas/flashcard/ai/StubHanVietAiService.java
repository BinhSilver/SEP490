package com.jlas.flashcard.ai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline dictionary fallback — not registered as a bean.
 * {@link OpenRouterHanVietAiService} is the primary implementation.
 */
public final class StubHanVietAiService {

    private StubHanVietAiService() {
    }

    private static final Map<String, String> KNOWN = Map.of(
            "台風", "Đài phong",
            "位置", "Vị trí",
            "景色", "Cảnh sắc",
            "陽気", "Dương khí",
            "加えて", "Gia"
    );

    public static String lookup(String front) {
        if (front == null || front.isBlank()) return "";
        return KNOWN.getOrDefault(front.trim(), "");
    }

    public static Map<Long, String> batch(List<HanVietAiService.HanVietRequest> items) {
        Map<Long, String> out = new HashMap<>();
        if (items == null) return out;
        for (HanVietAiService.HanVietRequest item : items) {
            if (item != null && item.id() != null) {
                out.put(item.id(), lookup(item.front()));
            }
        }
        return out;
    }
}
