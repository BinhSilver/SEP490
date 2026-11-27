package com.jlas.game.runtime.puzzle;

import com.jlas.common.exception.ApiException;
import com.jlas.common.exception.ErrorCode;
import com.jlas.game.dto.PuzzleDtos.InkAction;
import com.jlas.game.dto.PuzzleDtos.InkDelta;
import com.jlas.game.dto.PuzzleDtos.InkEdit;
import com.jlas.game.dto.PuzzleDtos.Segment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Board and recent editing history, accessed under the session lock. */
final class PuzzleDrawing {
    private static final int MAX_SEGMENTS = 4096;
    private static final int HISTORY_LIMIT = 24;

    private final List<Segment> board = new ArrayList<>();
    private final Deque<Change> undo = new ArrayDeque<>();
    private final Deque<Change> redo = new ArrayDeque<>();
    private long sequence;
    private Long openStrokeId;

    long sequence() { return sequence; }
    List<Segment> segments() { return List.copyOf(board); }
    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }

    void reset() {
        board.clear();
        undo.clear();
        redo.clear();
        sequence = 0;
        openStrokeId = null;
    }

    InkDelta apply(InkAction action) {
        if (action.edit() != null) {
            if (action.clear() || !action.segments().isEmpty() || action.strokeId() != null) throw invalidAction();
            moveHistory(action.edit());
            return delta(action.round(), true, segments());
        }
        if (action.clear() == !action.segments().isEmpty() || action.clear() && action.strokeId() != null) {
            throw invalidAction();
        }
        if (action.clear()) {
            if (!board.isEmpty()) {
                remember(new Change(true, segments()));
                board.clear();
            }
            openStrokeId = null;
            return delta(action.round(), true, List.of());
        }

        List<Segment> added = List.copyOf(action.segments());
        // A paint-bucket fill is a single point on its own, never an eraser mark.
        boolean filling = added.stream().anyMatch(Segment::fill);
        if (filling && (added.size() != 1 || added.getFirst().erase()
                || added.getFirst().x() != added.getFirst().toX() || added.getFirst().y() != added.getFirst().toY())) {
            throw invalidAction();
        }
        if (board.size() + added.size() > MAX_SEGMENTS) {
            throw new ApiException(ErrorCode.CONFLICT, "Bảng vẽ đã đầy. Hãy dùng Xóa bảng để vẽ tiếp.");
        }
        if (action.strokeId() != null && action.strokeId().equals(openStrokeId)) {
            undo.getLast().segments().addAll(added);
        } else {
            remember(new Change(false, new ArrayList<>(added)));
        }
        openStrokeId = action.strokeId();
        board.addAll(added);
        return delta(action.round(), false, added);
    }

    private void remember(Change change) {
        redo.clear();
        if (undo.size() == HISTORY_LIMIT) undo.removeFirst();
        undo.addLast(change);
    }

    private void moveHistory(InkEdit edit) {
        openStrokeId = null;
        if (edit == InkEdit.UNDO && canUndo()) {
            Change change = undo.removeLast();
            if (change.clear()) board.addAll(change.segments());
            else board.subList(board.size() - change.segments().size(), board.size()).clear();
            redo.addLast(change);
        } else if (edit == InkEdit.REDO && canRedo()) {
            Change change = redo.removeLast();
            if (change.clear()) board.clear();
            else board.addAll(change.segments());
            undo.addLast(change);
        }
    }

    private InkDelta delta(int round, boolean clear, List<Segment> segments) {
        return new InkDelta(round, ++sequence, clear, segments, canUndo(), canRedo());
    }

    private static ApiException invalidAction() {
        return new ApiException(ErrorCode.BAD_REQUEST, "Thao tác vẽ không hợp lệ");
    }

    private record Change(boolean clear, List<Segment> segments) { }
}
