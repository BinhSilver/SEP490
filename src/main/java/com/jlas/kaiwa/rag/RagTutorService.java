package com.jlas.kaiwa.rag;

import com.jlas.kaiwa.service.ai.LocalLlmClient;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Trả lời một câu hỏi có nguồn từ giáo trình J-LAS, bằng model trên máy chủ AI.
 * Dùng cho chatbot gia sư của người học và cho API đánh giá của ADMIN.
 */
@Service
public class RagTutorService {

    private static final int MAX_ANSWER_TOKENS = 700;

    private final RagRetriever retriever;
    private final LocalLlmClient llm;

    public RagTutorService(RagRetriever retriever, LocalLlmClient llm) {
        this.retriever = retriever;
        this.llm = llm;
    }

    public record Answer(String answer, List<RagRetriever.Passage> passages, RagLexiconLookup.Result lexicon,
                         List<RagPromptComposer.Citation> citations, long retrievalMs, long generationMs) { }

    public Answer ask(String question) {
        return ask(question, List.of());
    }

    /** @param history các lượt trước trong cùng cuộc trò chuyện (role user/assistant), đã được giới hạn độ dài */
    public Answer ask(String question, List<Map<String, String>> history) {
        long started = System.nanoTime();
        RagRetriever.Context context = retriever.retrieve(question, null);
        long retrieved = System.nanoTime();
        String answer = llm.chat(RagPromptComposer.systemPrompt(context), history, question, MAX_ANSWER_TOKENS);
        long done = System.nanoTime();
        return new Answer(answer, context.passages(), context.lexicon(), RagPromptComposer.citations(context),
                (retrieved - started) / 1_000_000, (done - retrieved) / 1_000_000);
    }
}
