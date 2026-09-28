package com.jlas.learning.service.impl;

import com.jlas.common.exception.*;
import com.jlas.learning.config.InteractiveLearningV2Properties;
import com.jlas.learning.dto.*;
import com.jlas.learning.entity.*;
import com.jlas.learning.enums.*;
import com.jlas.learning.repository.*;
import com.jlas.learning.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class InteractivePackageComposerImpl implements InteractivePackageComposer {
    private final InteractiveLearningV2Properties properties;
    private final InteractiveContentCatalog catalog;
    private final LearningPathPackageRepository packages;
    private final UserLearningPathItemRepository items;
    private final LearningActivityMemberRepository members;
    private final UserLearningStageRepository stages;

    @Override @Transactional
    public InteractiveCompositionResult compose(UserLearningPath path, List<ScheduledLearningResource> schedule) {
        if (!properties.isEnabled()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "Learning Path V2 chưa được bật.");
        if (path.getExperienceVersion()!=LearningExperienceVersion.INTERACTIVE_V2) throw new ApiException(ErrorCode.CONFLICT,"Composer V2 chỉ nhận path INTERACTIVE_V2.");
        // [JLAS-W0193] Task 9 fix: this idempotency/retry check must be scoped to GOAL_LEARNING packages
        // only. Warm-up B (WarmUpBComposerImpl) now composes WARM_UP packages for the same path BEFORE
        // this composer runs; an unscoped existsByLearningPathId would make this composer wrongly believe
        // target packages already exist (because warm-up ones do) and silently compose zero target
        // packages for every V2 path that has any warm-up content — a genuine integration-blocking bug,
        // not a hypothetical one, caught while wiring Task 9.
        boolean anyPackageExistsAlready = packages.existsByLearningPathId(path.getId());
        if (packages.existsByLearningPathIdAndPhase(path.getId(), LearningPhase.GOAL_LEARNING.name())) {
            long existingTargetCount = packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                    .filter(p -> LearningPhase.GOAL_LEARNING.name().equals(p.getPhase())).count();
            return new InteractiveCompositionResult(true, (int) existingTargetCount, List.of());
        }

        Map<Long,ScheduledLearningResource> targets=new LinkedHashMap<>();
        if(schedule!=null) schedule.stream().filter(s->s.phase()==LearningPhase.GOAL_LEARNING)
                .forEach(s->targets.putIfAbsent(s.resource().lessonId(),s));
        if(targets.isEmpty()) return new InteractiveCompositionResult(false,0,List.of(new CoverageGap("PATH:"+path.getId(),"MISSING_TARGET_PACKAGES")));

        List<Map.Entry<ScheduledLearningResource,InteractiveContentSnapshot>> inspected=new ArrayList<>();
        List<CoverageGap> gaps=new ArrayList<>();
        targets.values().forEach(seed->{var snapshot=catalog.inspect(seed.resource().lessonId(),seed.resource().level()); inspected.add(Map.entry(seed,snapshot)); gaps.addAll(snapshot.gaps());});
        if(!gaps.isEmpty()) return new InteractiveCompositionResult(false,0,gaps);

        int packageOrder=0; int activityOrder=items.findMaxOrderIndexByLearningPathId(path.getId());
        int firstTargetDay=items.findMaxDayNumberByLearningPathId(path.getId());
        // [JLAS-W0193] Task 9 fix: package order_index must continue after any Warm-up B packages already
        // saved for this path (uk_learning_package_path_order is unique per path) — restarting at 1 here
        // would collide with an existing warm-up package's order_index.
        int packageOrderBase = packages.findByLearningPathIdOrderByOrderIndex(path.getId()).stream()
                .mapToInt(LearningPathPackage::getOrderIndex).max().orElse(0);
        Map<String,Long> stageByLevel=new HashMap<>();
        stages.findByLearningPathIdOrderByStageIndexAsc(path.getId()).forEach(s->stageByLevel.put(s.getLevel(),s.getId()));
        for(var entry:inspected){
            var seed=entry.getKey(); var snapshot=entry.getValue(); packageOrder++;
            var learningPackage=packages.save(LearningPathPackage.builder().learningPath(path).sourceLesson(snapshot.lesson())
                    .contentRevision(snapshot.revision()).stageId(stageByLevel.get(seed.resource().level())).phase(LearningPhase.GOAL_LEARNING.name())
                    .dayNumber(firstTargetDay+packageOrder).orderIndex(packageOrderBase+packageOrder).title(seed.resource().title())
                    // [JLAS-W0193] Task 9 fix: only AVAILABLE when this is truly the first package in the
                    // whole path (no Warm-up B package precedes it) — otherwise the learner would see both
                    // a warm-up package and a target package startable at once, contradicting design spec
                    // §5.6 ("không gộp warm-up và target vào cùng ngày"). When warm-up packages exist, the
                    // first target package starts LOCKED and unlocks via the existing next-package-unlock
                    // mechanism (Task 8) once the last warm-up package completes.
                    .status(packageOrder==1&&!anyPackageExistsAlready?LearningPackageStatus.AVAILABLE:LearningPackageStatus.LOCKED)
                    .estimatedMinutes(Math.max(1,seed.estimatedMinutes())).build());
            activityOrder=saveActivity(path,learningPackage,LearningActivityType.VOCABULARY,LearningMemberType.VOCABULARY_ENTRY,snapshot.vocabularyIds(),snapshot.revision(),activityOrder);
            activityOrder=saveActivity(path,learningPackage,LearningActivityType.KANJI,LearningMemberType.KANJI,snapshot.kanjiIds(),snapshot.revision(),activityOrder);
            activityOrder=saveActivity(path,learningPackage,LearningActivityType.GRAMMAR,LearningMemberType.GRAMMAR_POINT,snapshot.grammarPointIds(),snapshot.revision(),activityOrder);
            activityOrder=saveActivity(path,learningPackage,LearningActivityType.DIALOGUE,LearningMemberType.DIALOGUE_LINE,snapshot.dialogueLineIds(),snapshot.revision(),activityOrder);
        }
        return new InteractiveCompositionResult(true,packageOrder,List.of());
    }

    private int saveActivity(UserLearningPath path,LearningPathPackage pkg,LearningActivityType type,
            LearningMemberType memberType,List<Long> ids,String revision,int globalOrder){
        int next=globalOrder+1;
        var activity=items.save(UserLearningPathItem.builder().learningPath(path).learningPackage(pkg).lesson(pkg.getSourceLesson())
                .orderIndex(next).phase(LearningPhase.GOAL_LEARNING.name()).dayNumber(pkg.getDayNumber())
                .estimatedMinutes(pkg.getEstimatedMinutes()).activityType(type).activityState(LearningActivityState.LOCKED).build());
        List<LearningActivityMember> rows=new ArrayList<>(); int index=0;
        for(Long id:new LinkedHashSet<>(ids)) rows.add(LearningActivityMember.builder().activity(activity).memberType(memberType)
                .canonicalId(id).orderIndex(++index).sourceRevision(revision).build());
        members.saveAll(rows);
        return next;
    }
}
