package com.jlas.learning;

import com.jlas.auth.entity.User;
import com.jlas.dictionary.entity.ContentItem;
import com.jlas.dictionary.entity.Lesson;
import com.jlas.dictionary.entity.Textbook;
import com.jlas.dictionary.entity.Unit;
import com.jlas.dictionary.repository.ContentItemRepository;
import com.jlas.dictionary.repository.LessonRepository;
import com.jlas.dictionary.repository.TextbookRepository;
import com.jlas.learning.config.InteractiveLearningV2Properties;
import com.jlas.learning.dto.LearningProfileSnapshot;
import com.jlas.learning.entity.LearningActivityMember;
import com.jlas.learning.entity.LearningPathPackage;
import com.jlas.learning.entity.UserLearningPath;
import com.jlas.learning.entity.UserLearningPathItem;
import com.jlas.learning.entity.UserLearningStage;
import com.jlas.learning.enums.LearningActivityType;
import com.jlas.learning.enums.LearningExperienceVersion;
import com.jlas.learning.enums.LearningMemberType;
import com.jlas.learning.repository.LearningActivityMemberRepository;
import com.jlas.learning.repository.LearningPathPackageRepository;
import com.jlas.learning.repository.UserLearningPathItemRepository;
import com.jlas.learning.repository.UserLearningStageRepository;
import com.jlas.learning.service.impl.FoundationWarmUpComposerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** [JLAS-W0108] Foundation composer: from-zero learners get one WARM_UP package per kana chart lesson,
 * a single FOUNDATION activity per package — never a whole-lesson four-part target package (spec §2.2's
 * exception). Learners who already know the alphabet (assessedLevel != BELOW_N5) get nothing from this
 * composer at all. */
@ExtendWith(MockitoExtension.class)
class FoundationWarmUpComposerTest {
    @Mock TextbookRepository textbooks;
    @Mock LessonRepository lessons;
    @Mock ContentItemRepository contentItems;
    @Mock LearningPathPackageRepository packages;
    @Mock UserLearningPathItemRepository items;
    @Mock LearningActivityMemberRepository members;
    @Mock UserLearningStageRepository stages;

    InteractiveLearningV2Properties properties;
    FoundationWarmUpComposerImpl composer;

    @BeforeEach
    void setUp() {
        properties = new InteractiveLearningV2Properties();
        properties.setEnabled(true);
        composer = new FoundationWarmUpComposerImpl(properties, textbooks, lessons, contentItems,
                packages, items, members, stages);
        lenient().when(packages.existsByLearningPathIdAndPhase(anyLong(), eq("WARM_UP"))).thenReturn(false);
        lenient().when(packages.existsByLearningPathId(anyLong())).thenReturn(false);
        lenient().when(packages.findByLearningPathIdOrderByOrderIndex(anyLong())).thenReturn(List.of());
        lenient().when(items.findMaxOrderIndexByLearningPathId(anyLong())).thenReturn(0);
        lenient().when(items.findMaxDayNumberByLearningPathId(anyLong())).thenReturn(0);
        lenient().when(stages.findByLearningPathIdOrderByStageIndexAsc(anyLong())).thenReturn(List.of(
                UserLearningStage.builder().id(21L).level("FOUNDATION").stageIndex(0).status("ACTIVE").build()));
        lenient().when(packages.save(any())).thenAnswer(i -> {
            var p = i.getArgument(0, LearningPathPackage.class);
            p.setId((long) p.getOrderIndex());
            return p;
        });
        lenient().when(items.save(any())).thenAnswer(i -> {
            var item = i.getArgument(0, UserLearningPathItem.class);
            item.setId((long) item.getOrderIndex());
            return item;
        });
    }

    private UserLearningPath path() {
        return UserLearningPath.builder().id(5L).user(User.builder().id(7L).build())
                .experienceVersion(LearningExperienceVersion.INTERACTIVE_V2).title("P").build();
    }

    private LearningProfileSnapshot profile(String assessedLevel) {
        return new LearningProfileSnapshot(7L, null, 1L, "rev-1", true, false,
                assessedLevel, "N5", "JLPT", 30, List.of());
    }

    private Lesson kanaLesson(long id, String title) {
        var textbook = Textbook.builder().id(3L).jlptLevel(null).isLearningPathSource(true).build();
        var unit = Unit.builder().id(11L).textbook(textbook).build();
        return Lesson.builder().id(id).unit(unit).title(title).status("PUBLISHED").updatedAt(LocalDateTime.now()).build();
    }

    private ContentItem character(long id, String romaji, String kana) {
        return ContentItem.builder().id(id).contentType("READING").title(romaji).content(kana).build();
    }

    @Test void nonFromZeroLearnerGetsNothingFromThisComposer() {
        var result = composer.composeFoundation(path(), profile("N5"));

        assertEquals(0, result.packageCount());
        assertTrue(result.gaps().isEmpty());
        verifyNoInteractions(textbooks, lessons, contentItems, packages, items, members);
    }

    @Test void fromZeroLearnerGetsOnePackagePerKanaLessonWithASingleFoundationActivity() {
        when(textbooks.findKanaFoundationByStatus("PUBLISHED"))
                .thenReturn(List.of(Textbook.builder().id(3L).build()));
        var lesson1 = kanaLesson(51L, "Bài 1: Hàng あ, か, さ");
        var lesson2 = kanaLesson(52L, "Bài 2: Hàng た, な, は");
        when(lessons.findByTextbookIdAndStatus(3L, "PUBLISHED")).thenReturn(List.of(lesson1, lesson2));
        when(contentItems.findByLessonIdAndContentType(51L, "READING"))
                .thenReturn(List.of(character(1, "a", "あ"), character(2, "ka", "か"), character(3, "sa", "さ")));
        when(contentItems.findByLessonIdAndContentType(52L, "READING"))
                .thenReturn(List.of(character(4, "ta", "た"), character(5, "na", "な")));

        var result = composer.composeFoundation(path(), profile("BELOW_N5"));

        assertTrue(result.published());
        assertEquals(2, result.packageCount());
        assertTrue(result.gaps().isEmpty());

        var packageCaptor = ArgumentCaptor.forClass(LearningPathPackage.class);
        verify(packages, times(2)).save(packageCaptor.capture());
        assertTrue(packageCaptor.getAllValues().stream().allMatch(p -> "WARM_UP".equals(p.getPhase())));

        var activityCaptor = ArgumentCaptor.forClass(UserLearningPathItem.class);
        verify(items, times(2)).save(activityCaptor.capture());
        assertTrue(activityCaptor.getAllValues().stream()
                .allMatch(a -> a.getActivityType() == LearningActivityType.FOUNDATION));

        var memberCaptor = ArgumentCaptor.forClass(List.class);
        verify(members, times(2)).saveAll(memberCaptor.capture());
        assertEquals(3, memberCaptor.getAllValues().get(0).size());
        assertEquals(2, memberCaptor.getAllValues().get(1).size());
        @SuppressWarnings("unchecked")
        List<LearningActivityMember> firstRows = memberCaptor.getAllValues().get(0);
        assertTrue(firstRows.stream().allMatch(r -> r.getMemberType() == LearningMemberType.FOUNDATION_CHARACTER));
    }

    @Test void foundationPackageUsesTheFoundationStageRatherThanTheFirstStage() {
        var path = path();
        when(stages.findByLearningPathIdOrderByStageIndexAsc(5L)).thenReturn(List.of(
                UserLearningStage.builder().id(20L).learningPath(path).level("N5").stageIndex(0).build(),
                UserLearningStage.builder().id(21L).learningPath(path).level("FOUNDATION").stageIndex(1).build()));
        when(textbooks.findKanaFoundationByStatus("PUBLISHED"))
                .thenReturn(List.of(Textbook.builder().id(3L).build()));
        var lesson = kanaLesson(51L, "Bài 1");
        when(lessons.findByTextbookIdAndStatus(3L, "PUBLISHED")).thenReturn(List.of(lesson));
        when(contentItems.findByLessonIdAndContentType(51L, "READING"))
                .thenReturn(List.of(character(1, "a", "あ")));

        composer.composeFoundation(path, profile("BELOW_N5"));

        verify(packages).save(argThat(value -> Long.valueOf(21L).equals(value.getStageId())));
    }

    @Test void fromZeroLearnerWithNoKanaTextbookGetsAGapNotAnException() {
        when(textbooks.findKanaFoundationByStatus("PUBLISHED")).thenReturn(List.of());

        var result = composer.composeFoundation(path(), profile("BELOW_N5"));

        assertTrue(result.published());
        assertEquals(0, result.packageCount());
        assertEquals(1, result.gaps().size());
        assertEquals("MISSING_KANA_FOUNDATION_CONTENT", result.gaps().get(0).reasonCode());
        verify(packages, never()).save(any());
        verify(items, never()).save(any());
        verify(members, never()).saveAll(any());
    }

    @Test void lessonWithNoCharactersIsSkippedWithAGapInsteadOfAnEmptyActivity() {
        when(textbooks.findKanaFoundationByStatus("PUBLISHED"))
                .thenReturn(List.of(Textbook.builder().id(3L).build()));
        var lesson1 = kanaLesson(51L, "Bài 1");
        when(lessons.findByTextbookIdAndStatus(3L, "PUBLISHED")).thenReturn(List.of(lesson1));
        when(contentItems.findByLessonIdAndContentType(51L, "READING")).thenReturn(List.of());

        var result = composer.composeFoundation(path(), profile("BELOW_N5"));

        assertEquals(0, result.packageCount());
        assertEquals(1, result.gaps().size());
        assertEquals("MISSING_KANA_CHARACTERS", result.gaps().get(0).reasonCode());
        verify(packages, never()).save(any());
        verify(items, never()).save(any());
        verify(members, never()).saveAll(any());
    }
}
