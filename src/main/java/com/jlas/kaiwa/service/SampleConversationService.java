package com.jlas.kaiwa.service;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.kaiwa.dto.FuriganaTokenDto;
import com.jlas.kaiwa.model.Scenario;
import com.jlas.kaiwa.model.ScenarioLearningContent;
import com.jlas.kaiwa.repository.ScenarioRepository;
import com.jlas.kaiwa.service.learning.AnnotationService;
import com.jlas.kaiwa.service.scenario.ScenarioContentService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only authored dialogue; opening a sample never creates a practice session. */
@Service
public class SampleConversationService {
    private final ScenarioRepository scenarios;
    private final ScenarioContentService content;
    private final AnnotationService annotations;

    public SampleConversationService(ScenarioRepository scenarios, ScenarioContentService content,
                                     AnnotationService annotations) {
        this.scenarios = scenarios;
        this.content = content;
        this.annotations = annotations;
    }

    @Transactional(readOnly = true)
    public SamplePage page(Long scenarioId, String requestedCode) {
        Scenario scenario = scenarios.findActiveDetail(scenarioId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy kịch bản."));
        var branches = content.branchDialogues(scenario);
        String code = requestedCode == null || requestedCode.isBlank()
                ? (scenario.getParentScenario() == null && !branches.isEmpty() ? branches.getFirst().code() : "example")
                : requestedCode;
        String title;
        List<ScenarioLearningContent.ExampleDialogue> dialogue;
        if ("example".equals(code)) {
            title = scenario.getVariantTitle() != null ? scenario.getVariantTitle() : scenario.getTitle();
            dialogue = content.dialogues(scenario).stream().map(line -> new ScenarioLearningContent.ExampleDialogue(
                    line.getSpeaker(), line.getContentJa(), line.getTranslation())).toList();
        } else {
            var branch = branches.stream().filter(item -> code.equals(item.code())).findFirst()
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "Không tìm thấy hội thoại mẫu này."));
            title = branch.title();
            dialogue = branch.exampleDialogue();
        }
        if (dialogue == null || dialogue.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "Kịch bản chưa có hội thoại mẫu.");
        }
        var lines = dialogue.stream().map(line -> new SampleLine(line.speaker(), line.text().trim(),
                line.translation().trim(), annotations.annotateFurigana(scenarioId, line.text().trim(), false))).toList();
        String background = content.flow(scenario).stream()
                .flatMap(flow -> flow.stages() == null ? java.util.stream.Stream.empty() : flow.stages().stream())
                .filter(stage -> code.equals(stage.code())).map(stage -> stage.imageUrl())
                .filter(url -> url != null && url.matches("/images/[a-zA-Z0-9/_.-]+\\.(webp|png|jpg)"))
                .findFirst().orElse("/images/scenarios/" + scenario.getSlug() + ".png");
        return new SamplePage(scenario, code, title, lines, background);
    }

    public record SampleLine(String speaker, String text, String translation, List<FuriganaTokenDto> tokens) {}
    public record SamplePage(Scenario scenario, String code, String title, List<SampleLine> lines, String backgroundUrl) {}
}
