package com.jlas.mission.util;

import com.jlas.mission.util.KanaPuzzle.Cell;
import java.util.*;

/** Dense word-path boards. All letters come from selected DB readings, including the fallback. */
public final class MultiWordPuzzleGenerator {
    private MultiWordPuzzleGenerator() {}
    public record Entry(long id, String expression, String reading, String meaning, String level, List<String> tokens) {}
    public record Solution(long wordId, List<Integer> path) {}
    public record Puzzle(int version, int gridSize, List<Cell> cells, List<Entry> words,
                         List<Solution> solutions, boolean fallback) {}
    public record Limits(int gridSize, int minWords, int maxWords, int searchBudget) {
        public Limits {
            if (gridSize < 4 || gridSize > 8 || minWords < 2 || maxWords < minWords || maxWords > 15 || searchBudget < 0) {
                throw new IllegalArgumentException("Expected a 4..8 grid and at least two words");
            }
        }
    }

    public static Optional<Puzzle> generate(List<Entry> vocabulary, Limits limits, long seed, boolean mixed) {
        Random random = new Random(seed);
        Map<String, Entry> unique = new LinkedHashMap<>();
        List<Entry> candidates = new ArrayList<>(vocabulary);
        Collections.shuffle(candidates, random);
        for (Entry word : candidates) {
            if (word.tokens().size() >= 2 && word.tokens().size() <= 12) {
                unique.putIfAbsent(String.join("", word.tokens()), word);
            }
        }
        if (mixed && !unique.isEmpty()) {
            Entry first = unique.values().iterator().next();
            candidates.stream().filter(w -> w.tokens().size() >= 2 && w.tokens().size() <= 12
                    && !w.level().equals(first.level()) && !w.tokens().equals(first.tokens()))
                    .findFirst().ifPresent(w -> unique.put(String.join("", w.tokens()), w));
        }
        List<Entry> pool = new ArrayList<>(unique.values());
        Collections.shuffle(pool, random);
        List<Entry> selected = pack(pool, limits, mixed);
        if (selected.isEmpty()) return Optional.empty();
        Collections.shuffle(selected, random);
        int size = limits.gridSize(), count = selected.stream().mapToInt(w -> w.tokens().size()).sum();
        List<Integer> walk = new ArrayList<>();
        int[] budget = {limits.searchBudget()};
        boolean placed = walk(size, count, random.nextInt(size * size), 0L, walk, random, budget);
        if (!placed) walk = fallbackWalk(size, seed).subList(0, count);
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < size * size; i++) cells.add(new Cell(i, i / size, i % size, null));
        List<Solution> solutions = new ArrayList<>();
        int cursor = 0;
        for (Entry word : selected) {
            List<Integer> path = new ArrayList<>();
            for (String token : word.tokens()) {
                int id = walk.get(cursor++);
                cells.set(id, new Cell(id, id / size, id % size, token));
                path.add(id);
            }
            solutions.add(new Solution(word.id(), List.copyOf(path)));
        }
        Puzzle puzzle = new Puzzle(2, size, List.copyOf(cells), List.copyOf(selected), List.copyOf(solutions), !placed);
        if (!PuzzleSolver.verify(puzzle)) throw new IllegalStateException("Generated board failed complete-cover verification");
        return Optional.of(puzzle);
    }

    /** Bounded 0/1 knapsack: choose distinct words, at least 80% coverage, never padding letters. */
    private static List<Entry> pack(List<Entry> pool, Limits limits, boolean mixed) {
        int capacity = limits.gridSize() * limits.gridSize();
        List<Entry> anchors = new ArrayList<>();
        if (mixed && pool.stream().map(Entry::level).distinct().count() > 1) {
            List<Entry> shortest = pool.stream().sorted(Comparator.comparingInt(w -> w.tokens().size())).toList();
            anchors.add(shortest.getFirst());
            shortest.stream().filter(w -> !w.level().equals(anchors.getFirst().level())).findFirst().ifPresent(anchors::add);
        }
        int anchorLength = anchors.stream().mapToInt(w -> w.tokens().size()).sum();
        if (anchorLength > capacity) return List.of();
        @SuppressWarnings("unchecked")
        List<Entry>[][] dp = (List<Entry>[][]) new List<?>[limits.maxWords() + 1][capacity + 1];
        dp[anchors.size()][anchorLength] = anchors;
        // At most 32 candidates of each length keeps even a large dictionary bounded.
        Map<Integer, Integer> perLength = new HashMap<>();
        for (Entry word : pool) {
            if (anchors.contains(word)) continue;
            int length = word.tokens().size();
            if (perLength.merge(length, 1, Integer::sum) > 32) continue;
            for (int n = limits.maxWords(); n > anchors.size(); n--) {
                for (int total = capacity; total >= length; total--) {
                    if (dp[n][total] == null && dp[n - 1][total - length] != null) {
                        List<Entry> next = new ArrayList<>(dp[n - 1][total - length]);
                        next.add(word); dp[n][total] = next;
                    }
                }
            }
        }
        for (int total = capacity; total >= Math.ceil(capacity * .8); total--) {
            for (int n = limits.minWords(); n <= limits.maxWords(); n++) {
                if (dp[n][total] != null) return new ArrayList<>(dp[n][total]);
            }
        }
        return List.of();
    }

    /** Randomized backtracking places one non-overlapping route, partitioned into word paths. */
    private static boolean walk(int size, int count, int id, long used, List<Integer> path, Random random, int[] budget) {
        if (--budget[0] < 0) return false;
        path.add(id); used |= 1L << id;
        if (path.size() == count) return true;
        List<Integer> options = neighbors(id, size).stream().filter(next -> (usedBit(path, next)) == false)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Collections.shuffle(options, random);
        long occupied = used;
        options.sort(Comparator.comparingLong(next -> neighbors(next, size).stream().filter(n -> (occupied & (1L << n)) == 0).count()));
        for (int next : options) if (walk(size, count, next, used, path, random, budget)) return true;
        path.removeLast();
        return false;
    }

    private static boolean usedBit(List<Integer> path, int next) { return path.contains(next); }

    public static List<Integer> neighbors(int id, int size) {
        List<Integer> neighbors = new ArrayList<>(4);
        if (id / size > 0) neighbors.add(id - size);
        if (id / size + 1 < size) neighbors.add(id + size);
        if (id % size > 0) neighbors.add(id - 1);
        if (id % size + 1 < size) neighbors.add(id + 1);
        return neighbors;
    }

    /** A verified full-grid snake, rotated/reflected by the seed, using the same DB word pack. */
    private static List<Integer> fallbackWalk(int size, long seed) {
        List<Integer> result = new ArrayList<>();
        for (int row = 0; row < size; row++) for (int col = 0; col < size; col++) {
            int r = row, c = row % 2 == 0 ? col : size - 1 - col;
            if ((seed & 1) != 0) c = size - 1 - c;
            if ((seed & 2) != 0) r = size - 1 - r;
            if ((seed & 4) != 0) { int swap = r; r = c; c = swap; }
            result.add(r * size + c);
        }
        return result;
    }
}
