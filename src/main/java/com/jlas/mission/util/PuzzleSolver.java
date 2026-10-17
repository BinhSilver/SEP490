package com.jlas.mission.util;

import com.jlas.mission.util.MultiWordPuzzleGenerator.*;
import java.util.*;

/** Checks complete board coverage, not just whether each individual reading can be spelled. */
public final class PuzzleSolver {
    private PuzzleSolver() {}
    public record Search(int solutions, boolean budgetExhausted) {}

    public static boolean verify(Puzzle puzzle) {
        if (puzzle.words().size() < 2 || puzzle.cells().size() != puzzle.gridSize() * puzzle.gridSize()
                || puzzle.solutions().size() != puzzle.words().size()) return false;
        Set<Long> words = new HashSet<>();
        Set<Integer> used = new HashSet<>();
        for (Solution solution : puzzle.solutions()) {
            Entry word = puzzle.words().stream().filter(w -> w.id() == solution.wordId()).findFirst().orElse(null);
            if (word == null || !words.add(word.id()) || solution.path().size() != word.tokens().size()) return false;
            int previous = -1;
            for (int i = 0; i < solution.path().size(); i++) {
                int id = solution.path().get(i);
                if (id < 0 || id >= puzzle.cells().size() || !used.add(id)) return false;
                var cell = puzzle.cells().get(id);
                if (cell.id() != id || !word.tokens().get(i).equals(cell.kana())) return false;
                if (previous >= 0 && !KanaPuzzle.adjacent(puzzle.cells().get(previous), cell)) return false;
                previous = id;
            }
        }
        long playable = puzzle.cells().stream().filter(c -> c.kana() != null).count();
        return used.size() == playable && playable >= Math.ceil(puzzle.cells().size() * .8);
    }

    /** Optional bounded global search for ambiguity; exhausted is distinct from unsolvable. */
    public static Search countSolutions(Puzzle puzzle, int limit, int budget) {
        if (limit < 1 || budget < 1) return new Search(0, true);
        List<Entry> words = puzzle.words().stream().sorted(Comparator.comparingInt((Entry w) -> w.tokens().size()).reversed()).toList();
        int[] remaining = {budget};
        int count = solve(puzzle, words, 0, 0L, limit, remaining);
        return new Search(count, remaining[0] <= 0);
    }

    private static int solve(Puzzle puzzle, List<Entry> words, int index, long used, int limit, int[] budget) {
        if (--budget[0] <= 0) return 0;
        if (index == words.size()) return Long.bitCount(used) == puzzle.cells().stream().filter(c -> c.kana() != null).count() ? 1 : 0;
        int found = 0;
        for (var cell : puzzle.cells()) {
            if ((used & (1L << cell.id())) != 0 || !words.get(index).tokens().getFirst().equals(cell.kana())) continue;
            found += wordPath(puzzle, words, index, cell.id(), 0, used, limit - found, budget);
            if (found >= limit || budget[0] <= 0) break;
        }
        return found;
    }

    private static int wordPath(Puzzle puzzle, List<Entry> words, int index, int id, int token, long used, int limit, int[] budget) {
        if (--budget[0] <= 0 || (used & (1L << id)) != 0 || !words.get(index).tokens().get(token).equals(puzzle.cells().get(id).kana())) return 0;
        used |= 1L << id;
        if (token + 1 == words.get(index).tokens().size()) return solve(puzzle, words, index + 1, used, limit, budget);
        int found = 0;
        for (int next : MultiWordPuzzleGenerator.neighbors(id, puzzle.gridSize())) {
            found += wordPath(puzzle, words, index, next, token + 1, used, limit - found, budget);
            if (found >= limit || budget[0] <= 0) break;
        }
        return found;
    }
}
