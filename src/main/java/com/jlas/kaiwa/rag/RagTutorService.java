package com.jlas.kaiwa.rag;

import com.jlas.kaiwa.service.ai.OpenRouterClient;
import java.util.List;
import org.springframework.stereotype.Service;

/** Trả lời một câu hỏi có nguồn từ giáo trình J-LAS. Phase 2 chỉ mở cho ADMIN để đánh giá chất lượng. */
@Service
public class RagTutorService {

    private final RagRetriever retriever;
    private final OpenRouterClient llm;

    public RagTutorService(RagRetriever retriever, OpenRouterClient llm) {
        this.retriever = retriever;
        this.llm = llm;
    }

    public record Answer(String answer, List<RagRetriever.Passage> passages, RagLexiconLookup.Result lexicon,
                         long retrievalMs, long generationMs) { }

    public Answer ask(String question) {
        long started = System.nanoTime();
        RagRetriever.Context context = retriever.retrieve(question, null);
        long retrieved = System.nanoTime();
        String answer = llm.complete(RagPromptComposer.systemPrompt(context), List.of(), question);
        long done = System.nanoTime();
        return new Answer(answer, context.passages(), context.lexicon(),
                (retrieved - started) / 1_000_000, (done - retrieved) / 1_000_000);
    }
}
