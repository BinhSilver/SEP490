package com.jlas.kaiwa.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Conversation Topic Library — loads topic trees from classpath:prompts/topics/*.json
 * and injects opening/follow-up banks into the free-chat system prompt.
 */
@Service
public class FreeChatTopicLibrary {

    private static final Logger log = LoggerFactory.getLogger(FreeChatTopicLibrary.class);

    private static final Map<String, String> UI_ALIAS_TO_ID = Map.ofEntries(
            Map.entry("anime", "anime"),
            Map.entry("game", "game"),
            Map.entry("games", "game"),
            Map.entry("travel", "travel"),
            Map.entry("du lịch", "travel"),
            Map.entry("music", "music"),
            Map.entry("âm nhạc", "music"),
            Map.entry("food", "food"),
            Map.entry("ẩm thực", "food"),
            Map.entry("technology", "technology"),
            Map.entry("công nghệ", "technology"),
            Map.entry("programming", "technology"),
            Map.entry("lập trình", "technology"),
            Map.entry("movies", "movie"),
            Map.entry("movie", "movie"),
            Map.entry("phim", "movie"),
            Map.entry("sports", "sports"),
            Map.entry("thể thao", "sports"),
            Map.entry("study", "study"),
            Map.entry("job", "job")
    );

    private final ObjectMapper objectMapper;
    private final Map<String, TopicPack> packsById = new LinkedHashMap<>();
    private volatile boolean loaded;

    public FreeChatTopicLibrary(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Full topic library (legacy / docs). Prefer {@link #buildTopicLibraryBlockCompact} at runtime.
     */
    public String buildTopicLibraryBlock(List<String> favoriteTopics) {
        return buildTopicLibraryBlockCompact(favoriteTopics, 4, 3, 8);
    }

    /**
     * Compact topic bank for token-limited free-chat system prompts.
     *
     * @param maxPacks     max themes to inject
     * @param maxOpening   max opening lines per theme
     * @param maxFollowUp  max follow-up lines per theme
     */
    public String buildTopicLibraryBlockCompact(
            List<String> favoriteTopics, int maxPacks, int maxOpening, int maxFollowUp) {
        ensureLoaded();
        LinkedHashSet<String> ids = resolveIds(favoriteTopics);
        if (ids.isEmpty()) {
            ids.add("anime");
            ids.add("music");
        }
        // cap packs
        List<String> limited = new ArrayList<>();
        for (String id : ids) {
            limited.add(id);
            if (limited.size() >= Math.max(1, maxPacks)) break;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Topic bank (pick ONE follow-up; paraphrase; stay on entity)\n");
        for (String id : limited) {
            TopicPack pack = packsById.get(id);
            if (pack == null) continue;
            sb.append("## ").append(pack.label).append('\n');
            int o = 0;
            for (String q : pack.opening) {
                if (o >= maxOpening) break;
                sb.append("- open: ").append(q).append('\n');
                o++;
            }
            int f = 0;
            for (String q : pack.followUp) {
                if (f >= maxFollowUp) break;
                sb.append("- next: ").append(q).append('\n');
                f++;
            }
        }
        return sb.toString().trim();
    }

    private LinkedHashSet<String> resolveIds(List<String> favoriteTopics) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (favoriteTopics == null) return ids;
        for (String raw : favoriteTopics) {
            if (raw == null || raw.isBlank()) continue;
            String key = raw.trim().toLowerCase(Locale.ROOT);
            String id = UI_ALIAS_TO_ID.get(key);
            if (id == null) {
                // try pack id / alias match from loaded JSON
                id = findIdByAlias(raw.trim());
            }
            if (id != null && packsById.containsKey(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private String findIdByAlias(String raw) {
        ensureLoaded();
        String lower = raw.toLowerCase(Locale.ROOT);
        for (TopicPack p : packsById.values()) {
            if (p.id.equalsIgnoreCase(raw) || p.label.equalsIgnoreCase(raw)) return p.id;
            for (String a : p.aliases) {
                if (a != null && a.equalsIgnoreCase(raw)) return p.id;
                if (a != null && a.toLowerCase(Locale.ROOT).contains(lower)) return p.id;
            }
        }
        return null;
    }

    private void ensureLoaded() {
        if (loaded) return;
        synchronized (this) {
            if (loaded) return;
            try {
                PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
                Resource[] resources = resolver.getResources("classpath:prompts/topics/*.json");
                for (Resource res : resources) {
                    try (InputStream in = res.getInputStream()) {
                        JsonNode root = objectMapper.readTree(in);
                        TopicPack pack = new TopicPack();
                        pack.id = text(root, "id");
                        pack.label = text(root, "label");
                        if (pack.id == null || pack.id.isBlank()) {
                            String filename = res.getFilename();
                            if (filename != null && filename.endsWith(".json")) {
                                pack.id = filename.substring(0, filename.length() - 5);
                            }
                        }
                        if (pack.label == null || pack.label.isBlank()) pack.label = pack.id;
                        pack.aliases = stringList(root.get("aliases"));
                        pack.opening = stringList(root.get("opening"));
                        pack.followUp = stringList(root.get("follow_up"));
                        if (pack.id != null) {
                            packsById.put(pack.id, pack);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load topic pack {}: {}", res.getFilename(), e.getMessage());
                    }
                }
                log.info("Loaded {} free-chat topic packs", packsById.size());
            } catch (Exception e) {
                log.warn("Failed to scan topic library: {}", e.getMessage());
            }
            loaded = true;
        }
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static List<String> stringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node == null || !node.isArray()) return list;
        for (JsonNode n : node) {
            if (n != null && !n.isNull()) {
                String t = n.asText();
                if (t != null && !t.isBlank()) list.add(t.trim());
            }
        }
        return list;
    }

    private static final class TopicPack {
        String id;
        String label;
        List<String> aliases = new ArrayList<>();
        List<String> opening = new ArrayList<>();
        List<String> followUp = new ArrayList<>();
    }
}
