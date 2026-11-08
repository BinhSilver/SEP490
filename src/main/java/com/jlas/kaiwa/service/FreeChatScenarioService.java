package com.jlas.kaiwa.service;

import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures a hidden free-chat scenario exists so free chat can reuse
 * ConversationSession + AIConversationService without a separate engine.
 */
@Service
public class FreeChatScenarioService {

    private final ScenarioRepository scenarioRepository;
    private final CategoryRepository categoryRepository;
    private final AiRoleRepository aiRoleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PromptTemplateRepository promptTemplateRepository;

    public FreeChatScenarioService(
            ScenarioRepository scenarioRepository,
            CategoryRepository categoryRepository,
            AiRoleRepository aiRoleRepository,
            UserRoleRepository userRoleRepository,
            PromptTemplateRepository promptTemplateRepository) {
        this.scenarioRepository = scenarioRepository;
        this.categoryRepository = categoryRepository;
        this.aiRoleRepository = aiRoleRepository;
        this.userRoleRepository = userRoleRepository;
        this.promptTemplateRepository = promptTemplateRepository;
    }

    @Transactional
    public Scenario ensureFreeChatScenario() {
        return scenarioRepository.findBySlug(FreeChatSupport.SCENARIO_SLUG)
                .orElseGet(this::createFreeChatScenario);
    }

    private Scenario createFreeChatScenario() {
        Category category = categoryRepository.findByCode(FreeChatSupport.CATEGORY_CODE)
                .orElseGet(this::createHiddenCategory);

        PromptTemplate prompt = promptTemplateRepository.findFirstByName(FreeChatSupport.PROMPT_NAME)
                .orElseGet(this::createPrompt);

        AiRole aiRole = aiRoleRepository.findAll().stream()
                .filter(r -> r.getName() != null && r.getName().contains("Tán gẫu"))
                .findFirst()
                .orElseGet(() -> {
                    AiRole r = new AiRole();
                    r.setName("Bạn Nhật thân thiện");
                    r.setDescription("Người Nhật nói chuyện tự nhiên, tán gẫu hàng ngày");
                    r.setPersonality("Thân thiện, vui vẻ, tò mò, dễ gần");
                    r.setSpeechStyle("Tiếng Nhật tự nhiên, câu ngắn, có cảm xúc");
                    r.setDefaultPrompt("Đóng vai người Nhật thật. Không tiết lộ mình là AI.");
                    return aiRoleRepository.save(r);
                });

        UserRole userRole = userRoleRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> {
                    UserRole u = new UserRole();
                    u.setName("Người học");
                    u.setDescription("Người học tiếng Nhật đang tán gẫu tự do");
                    return userRoleRepository.save(u);
                });

        Scenario s = new Scenario();
        s.setCategory(category);
        s.setTitle("AI Tán Gẫu");
        s.setSlug(FreeChatSupport.SCENARIO_SLUG);
        s.setDescription("Hội thoại tự do với người Nhật — không kịch bản, không nhiệm vụ.");
        s.setJlptLevel("N5");
        s.setDifficulty("Free");
        // Schema requires estimated_minutes > 0. Free chat has no hard limit,
        // but this field is catalog metadata, so use a sensible default.
        s.setEstimatedMinutes(15);
        s.setAiRole(aiRole);
        s.setUserRole(userRole);
        s.setPromptTemplate(prompt);
        s.setActive(true);
        return scenarioRepository.save(s);
    }

    private Category createHiddenCategory() {
        Category c = new Category();
        c.setCode(FreeChatSupport.CATEGORY_CODE);
        c.setTitle("Hệ thống");
        c.setTitleJa("システム");
        c.setDescription("Category nội bộ — không hiển thị catalog.");
        c.setIcon("fa-solid fa-comments");
        c.setDisplayOrder(9999);
        // inactive  listCategories() không hiện
        c.setActive(false);
        return categoryRepository.save(c);
    }

    private PromptTemplate createPrompt() {
        PromptTemplate p = new PromptTemplate();
        p.setName(FreeChatSupport.PROMPT_NAME);
        p.setVersion("1.0");
        p.setActive(true);
        // Actual free-chat system prompt is built in PromptBuilderService;
        // keep a short marker here for DB visibility.
        p.setSystemPrompt("FREE_CHAT_MARKER");
        return promptTemplateRepository.save(p);
    }
}
