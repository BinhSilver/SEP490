package com.jlas.kaiwa.service.ai;

import com.jlas.kaiwa.memory.MemorySearch;
import com.jlas.kaiwa.model.ConversationMessage;
import java.util.*;

/** Bounded recent dialogue plus relevant older exchanges from THIS session only. */
final class FreeChatHistory {
    private FreeChatHistory() { }

    static List<Map<String, String>> select(List<ConversationMessage> transcript, String query) {
        int end = transcript.size();
        if (end > 0 && "user".equals(transcript.get(end - 1).getRole())
                && query.equals(transcript.get(end - 1).getContent())) end--;
        int recentStart = Math.max(0, end - 12);
        Set<String> terms = MemorySearch.words(query);
        List<Integer> older = new ArrayList<>();
        for (int i = 0; i < recentStart; i++) {
            ConversationMessage message = transcript.get(i);
            if ("user".equals(message.getRole()) && overlap(terms, message.getContent()) > 0) older.add(i);
        }
        older.sort(Comparator.<Integer>comparingLong(i -> overlap(terms, transcript.get(i).getContent()))
                .reversed().thenComparing(Comparator.reverseOrder()));
        TreeSet<Integer> selected = new TreeSet<>();
        int olderChars = 0;
        for (int i : older.stream().limit(6).toList()) {
            String content = transcript.get(i).getContent();
            if (content == null || olderChars + content.length() > 3500) continue;
            selected.add(i); olderChars += content.length();
            // Preserve the exchange, so an AI suggestion cannot be mistaken for a user fact.
            if (i + 1 < recentStart && "assistant".equals(transcript.get(i + 1).getRole())
                    && transcript.get(i + 1).getContent() != null
                    && olderChars + transcript.get(i + 1).getContent().length() <= 3500) {
                selected.add(i + 1); olderChars += transcript.get(i + 1).getContent().length();
            }
        }
        int recentChars = 0;
        for (int i = end - 1; i >= recentStart; i--) {
            String content = transcript.get(i).getContent();
            if (content == null || recentChars + content.length() > 5500) continue;
            selected.add(i); recentChars += content.length();
        }
        List<Map<String, String>> result = new ArrayList<>();
        for (int i : selected) {
            ConversationMessage message = transcript.get(i);
            result.add(Map.of("role", message.getRole(), "content", message.getContent()));
        }
        return result;
    }

    private static long overlap(Set<String> query, String content) {
        if (content == null) return 0;
        Set<String> terms = MemorySearch.words(content);
        return query.stream().filter(terms::contains).count();
    }
}
