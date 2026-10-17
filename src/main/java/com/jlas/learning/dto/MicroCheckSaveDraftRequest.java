package com.jlas.learning.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** [JLAS-W0113] Autosave payload: the issued attempt id plus the learner's full current selection set. The
 * server replaces the previous draft wholesale (last-write-wins across tabs), so the client sends every
 * selected question and omits (or nulls) an entry to unselect it. Empty list = clear all drafts. Autosave
 * never grades, spends an attempt, changes score/state or rewards — it only persists selections for restore. */
public record MicroCheckSaveDraftRequest(
        @NotNull Long attemptId,
        @NotNull List<@Valid MicroCheckDraftAnswer> answers
) { }
