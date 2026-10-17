package com.jlas.kaiwa.service.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jlas.common.exception.*;
import com.jlas.common.security.ContentReviewAccess;
import com.jlas.kaiwa.model.*;
import com.jlas.kaiwa.repository.ConversationSessionRepository;
import com.jlas.kaiwa.util.StageFlowSupport;
import com.jlas.kaiwa.util.SceneImageSupport;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
public class StageProgressService {
    private final ScenarioContentService content;
    private final ConversationSessionRepository sessions;
    private final ObjectMapper json;
    public StageProgressService(ScenarioContentService content,ConversationSessionRepository sessions,ObjectMapper json) {
        this.content=content;this.sessions=sessions;this.json=json;
    }
    public record StageView(ScenarioStage definition,String status,String statusLabel,
                            List<String> prerequisiteTitles,Long resumeSession,int attempts,BigDecimal bestScore,
                            List<StoryStep> story) {
        public boolean locked(){return "locked".equals(status);}
        public boolean passed(){return "passed".equals(status);}
    }
    public record StoryStep(String code,String title,String summary,String icon,boolean ending,String speakerName,String imageUrl) {}
    public record ProgressView(Scenario scenario,List<StageView> stages,int passed,int percent,
                               String recommendedCode,List<ConversationSession> recent,boolean reviewer) {}
    public List<ScenarioStage> definitions(Scenario scenario) {
        return content.flow(scenario).map(ScenarioCompactDefinition::stages).orElse(List.of());
    }
    public ProgressView view(Scenario scenario,Long userId) {
        var stages=definitions(scenario);
        var flow=content.flow(scenario).orElse(null);
        var storyStates=new HashMap<String,ScenarioCompactDefinition.State>();
        if(flow!=null)flow.states().forEach(s->storyStates.put(s.code(),s));
        var history=userId==null?List.<ConversationSession>of():sessions.findByUserIdAndScenario_IdOrderByStartedAtDescIdDesc(userId,scenario.getId());
        Set<String> passed=new HashSet<>();
        history.stream().filter(s->s.isStagePassed()&&!s.isContentReview()).map(ConversationSession::getStageCode).forEach(passed::add);
        boolean reviewer=ContentReviewAccess.allowed(userId);
        var byCode=new HashMap<String,ScenarioStage>();stages.forEach(s->byCode.put(s.code(),s));
        var views=new ArrayList<StageView>();String recommended=null;int count=0;
        for(var stage:stages) {
            boolean done=passed.contains(stage.code()),open=reviewer||passed.containsAll(stage.prerequisites());
            var attempts=history.stream().filter(s->stage.code().equals(s.getStageCode())&&s.isContentReview()==reviewer).toList();
            var active=attempts.stream().filter(s->s.getStatus()==SessionStatus.IN_PROGRESS).findFirst().orElse(null);
            String status=done?"passed":!open?"locked":active!=null?"active":"available";
            String label=switch(status){case "passed"->"Đã hoàn thành";case "locked"->"Đang khóa";case "active"->"Đang luyện";default->"Có thể luyện";};
            if(done)count++;
            if(recommended==null&&open&&!done)recommended=stage.code();
            views.add(new StageView(stage,status,label,stage.prerequisites().stream().map(c->byCode.get(c).title()).toList(),
                    active==null?null:active.getId(),attempts.size(),attempts.stream().map(ConversationSession::getOverallScore).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null),
                    java.util.stream.IntStream.range(0,stage.stateCodes().size()).mapToObj(i->{
                        String code=stage.stateCodes().get(i);var state=storyStates.get(code);
                        String actor=stage.speakerCodes()==null?null:stage.speakerCodes().get(i);
                        String name=stage.speakers()==null?null:stage.speakers().stream().filter(s->s.code().equals(actor)).map(ScenarioSpeaker::name).findFirst().orElse(null);
                        return new StoryStep(code,state.name(),state.summary(),state.icon(),flow.terminalStateCodes().contains(code),name,
                                SceneImageSupport.forStep(stage,i,flow,scenario.getThumbnail()));
                    }).toList()));
        }
        if(recommended==null&&!stages.isEmpty())recommended=stages.getFirst().code();
        return new ProgressView(scenario,views,count,stages.isEmpty()?0:(int)Math.round(100.0*count/stages.size()),
                recommended,history.stream().limit(8).toList(),reviewer);
    }
    public ScenarioStage requireUnlocked(Scenario scenario,Long userId,String requested) {
        if(userId==null)throw new ApiException(ErrorCode.UNAUTHORIZED);
        var progress=view(scenario,userId);
        if(progress.stages().isEmpty())return null;
        String code=requested==null||requested.isBlank()?progress.recommendedCode():requested;
        var stage=progress.stages().stream().filter(s->s.definition().code().equals(code)).findFirst()
                .orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND,"Không tìm thấy màn luyện này."));
        if(stage.locked())throw new ApiException(ErrorCode.AICALL_STAGE_LOCKED,"Hãy hoàn thành: "+String.join(", ",stage.prerequisiteTitles()));
        return stage.definition();
    }
    public Optional<ConversationSession> resumable(Scenario scenario,Long userId,String code) {
        return sessions.findByUserIdAndScenario_IdOrderByStartedAtDescIdDesc(userId,scenario.getId()).stream()
                .filter(s->Objects.equals(code,s.getStageCode())&&s.getStatus()==SessionStatus.IN_PROGRESS
                        &&s.isContentReview()==ContentReviewAccess.allowed(userId)).findFirst();
    }
    public String snapshot(ScenarioStage stage) {
        try{return json.writeValueAsString(stage);}catch(Exception e){throw new IllegalStateException(e);}
    }
    public List<ConversationSession> history(Long userId,Long scenarioId) {
        if(userId==null)throw new ApiException(ErrorCode.UNAUTHORIZED);
        return sessions.findUserHistory(userId,scenarioId,PageRequest.of(0,100));
    }
    public String stageTitle(ConversationSession session) {
        return session.getStageCode()==null?session.getScenario().getVariantTitle():StageFlowSupport.stage(session).title();
    }
}
