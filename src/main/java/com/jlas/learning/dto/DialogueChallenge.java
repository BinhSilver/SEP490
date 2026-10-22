package com.jlas.learning.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** [JLAS-W0108] Owner 2026-10-19 (G1–G3, §2.24): the optional "vượt ải" challenge of a communication dialogue. The
 * dialogue runs turn by turn; at each learner turn the learner picks the real reply among 3 options. The answer key
 * stays on the server; options carry no correctness flag. */
public final class DialogueChallenge {
    private DialogueChallenge() { }

    /** {@code situationTitle}: the situation the challenge belongs to, shown as its heading (owner 2026-10-21). */
    public record View(boolean available, boolean cleared, int rewardCredits, String learnerRole, String label,
            List<Turn> turns, String situationTitle) {
        public View {
            turns = turns == null ? List.of() : List.copyOf(turns);
        }
        public View(boolean available, boolean cleared, int rewardCredits, String learnerRole, String label, List<Turn> turns) {
            this(available, cleared, rewardCredits, learnerRole, label, turns, null);
        }
    }

    /** {@code lineId}: the learner's line in the script where this choice replaces the line. */
    public record Turn(int index, Long lineId, List<Option> options) {
        public Turn {
            options = List.copyOf(options);
        }
    }

    public record Option(String id, String japanese, String meaningVi, String furigana) { }

    public record Answer(int turn, boolean correct, String correctOptionId, String explanationVi) { }

    public record Result(boolean cleared, boolean firstClear, int creditsAwarded, int correctCount, int questionCount) { }

    public record AnswerRequest(int turn, @NotNull @Size(max = 4) String option) { }

    public record CompleteRequest(@NotNull @Size(max = 40) List<@NotNull @Size(max = 4) String> answers) { }
}
