package com.jlas.dictionary.handwriting;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/**
 * So khớp bộ nét người dùng vẽ với dữ liệu nét chuẩn KanjiVG.
 *
 * <p>KanjiVG chỉ là dữ liệu nét chuẩn; đây là phần thuật toán nhận diện. Mỗi nét được lấy mẫu lại
 * thành {@link #POINTS} điểm rồi ghép cặp nét-với-nét theo khoảng cách hình học, KHÔNG dựa vào thứ
 * tự nét, nên sai thứ tự nét vẫn nhận được. Nét chuẩn chưa được vẽ chỉ bị phạt nhẹ (vẽ dở vẫn có
 * ứng viên); nét thừa của người dùng bị phạt nặng hơn. Thứ tự nét chỉ dùng để phân xử khi hai cách
 * ghép gần bằng nhau.</p>
 *
 * <p>Số nét CHỈ là một tín hiệu, không phải bộ lọc: vẽ tách nét, gộp nét hay vẽ dở đều được chấm
 * theo hình. Nét vẽ thừa bị phạt theo lượng mực nằm ngoài chữ, còn nét chuẩn chưa được vẽ chỉ bị
 * phạt nhẹ — nhờ đó bản vẽ chưa xong vẫn có nhiều ứng viên hợp lý. Khi đường ghép nét giải thích kém
 * một ứng viên có nhiều nét chuẩn hơn hẳn (dấu hiệu đã gộp nét), đường cứu hộ so mực hai chiều mà
 * không phụ thuộc ranh giới nét. Mọi ứng viên dùng CHUNG một thang điểm nên không nét đơn lẻ nào
 * khớp quá tốt có thể đè cả hình vẽ tổng thể.</p>
 *
 * <p>Giới hạn: ghép nét tham lam thay vì thuật toán Hungarian; không so khớp theo bộ thủ nên các chữ
 * chỉ khác nhau một nét nhỏ có thể xếp sát nhau.</p>
 */
public final class StrokeMatcher {

    /** Không gian toạ độ của KanjiVG (viewBox 109x109). */
    public static final double FRAME = 109;
    static final int POINTS = 16;

    private static final double MISSING_PENALTY = 0.03;
    /**
     * Nét người dùng vẽ mà không ghép được với nét chuẩn nào bị phạt THEO LƯỢNG MỰC nằm ngoài chữ,
     * không phải một mức cố định: nét thừa nằm đè lên chữ (vẽ tách nét) gần như không tốn gì, còn nét
     * dài đi xuyên ra ngoài chữ thì rất đắt. Đây là điều kiện để "vẽ nhiều nét hơn số nét chuẩn" vẫn
     * nhận ra chữ, mà nét rác vẫn bị loại.
     */
    private static final double EXTRA_STROKE_FLOOR = 0.06;
    private static final double EXTRA_INK_WEIGHT = 1.4;
    private static final double ORDER_WEIGHT = 0.003;
    private static final double FIT_PENALTY = 0.02;
    private static final double REVERSE_PENALTY = 0.03;
    private static final double INK_TOLERANCE = 6;
    private static final int INK_GRID = 110;
    private static final double INK_POINT_SPACING = 2.5;
    private static final int MAX_USER_INK_POINTS = 2048;
    private static final double MAX_INK_COVERAGE_ERROR = 0.32;
    private static final double MAX_MERGED_INK_COVERAGE_ERROR = 0.22;
    private static final double MAX_FITTED_RETRY_ERROR = 0.45;
    private static final double MAX_SHORT_DRAWING_FITTED_RETRY_ERROR = 0.60;
    private static final double INK_RESCUE_MEAN_COST = 0.08;
    private static final double INK_TAIL_WEIGHT = 0.5;
    private static final double INK_STROKE_SCORE_WEIGHT = 0.12;
    private static final double MAX_INK_RESCUE_SCORE = 0.18;
    private static final int INK_RESCUE_CANDIDATES_PER_SIGNAL = 64;
    /** Dải co giãn chấp nhận được giữa khung bao bản vẽ và khung bao chữ chuẩn. */
    private static final double MIN_FIT_SCALE = 0.5;
    private static final double MAX_FIT_SCALE = 2.0;
    /** Dung sai khung bao của bộ lọc thô cho đường cứu hộ (đơn vị KanjiVG). */
    private static final double BOX_TOLERANCE = 3 * INK_TOLERANCE;
    private static final int MAX_MERGE_ALLOWANCE = 2;
    /**
     * Sai lệch trung bình tối đa (chia cho khung 109) của các nét đã ghép. Đo trên 2.136 Kanji: chữ đúng
     * vẽ ẩu (nhiễu 6/109) tối đa ~0,14; nét nguệch ngoạc tốt nhất từ ~0,155; chấm ở góc ~0,16-0,19.
     */
    static final double MAX_MEAN_COST = 0.15;

    /** Nét chuẩn và các chỉ mục mực dựng sẵn của một Kanji. */
    public record Ref(String character, double[][] strokes, double[] bounds, double inkLength, BitSet inkMask,
                      byte[] inkDistanceMap) {
        public static Ref of(String character, List<List<double[]>> polylines) {
            double[][] strokes = polylines.stream().map(StrokeMatcher::resample).toArray(double[][]::new);
            return new Ref(character, strokes, StrokeMatcher.bounds(strokes), StrokeMatcher.inkLength(strokes),
                    StrokeMatcher.inkMask(strokes), StrokeMatcher.inkDistanceMap(strokes));
        }
    }

    private StrokeMatcher() {
    }

    /** Điểm của một ứng viên; {@code inkRescue} cho biết đường mực đã thay điểm ghép nét kém. */
    record Scored(String character, double score, double meanCost, boolean inkRescue) {
    }

    private record InkScore(double meanDistance, double tailDistance, double coverageError) {
    }

    /** Chấm đường mực; chỉ tính khoảng cách khi độ phủ đã qua ngưỡng, vì phép tính này đắt hơn. */
    private static InkScore scoreInk(double error, double[][] userInk, byte[] userMap, Ref ref) {
        if (error > MAX_INK_COVERAGE_ERROR) {
            return new InkScore(Double.MAX_VALUE, Double.MAX_VALUE, error);
        }
        return new InkScore(inkMeanDistance(userInk, userMap, ref.strokes(), ref.inkDistanceMap()),
                Math.max(inkTailMeanDistance(userInk, ref.inkDistanceMap(), 0.25),
                        inkTailMeanDistance(ref.strokes(), userMap, 0.25)), error);
    }

    /**
     * Trả về tối đa {@code limit} ký tự khớp nhất, tốt nhất đứng đầu. Ứng viên mà các nét ghép được
     * vẫn lệch quá nhiều so với nét chuẩn (nét nguệch ngoạc, chấm ở góc...) bị loại, nên input rác
     * cho danh sách rỗng thay vì "ứng viên tự tin nhưng sai".
     */
    public static List<String> rank(List<Ref> index, List<List<double[]>> input, int limit) {
        if (limit <= 0 || input.isEmpty()) return List.of();
        // Một thang điểm duy nhất cho mọi ứng viên: không ưu tiên cứng đường nào, nếu không một nét
        // đơn khớp hoàn hảo (ví dụ nét ngang cuối) sẽ đè cả hình vẽ tổng thể.
        return scoreAll(index, input).stream()
                .filter(s -> s.meanCost() <= MAX_MEAN_COST || s.inkRescue())
                .sorted(Comparator.comparingDouble(Scored::score))
                .limit(limit)
                .map(Scored::character)
                .toList();
    }

    /** Chấm mọi ứng viên còn lại sau bước loại nhanh theo số nét (chưa lọc ngưỡng, chưa sắp xếp). */
    static List<Scored> scoreAll(List<Ref> index, List<List<double[]>> input) {
        if (input.isEmpty()) return List.of();
        double[][] user = input.stream().map(StrokeMatcher::resample).toArray(double[][]::new);
        double[] userBounds = bounds(user);

        List<Scored> scored = new ArrayList<>();
        List<Ref> candidates = new ArrayList<>();
        for (Ref ref : index) {
            int k = user.length, n = ref.strokes().length;
            // Chặn trên theo TỶ LỆ chỉ để giữ chi phí tính toán, không phải bộ lọc theo số nét: vẽ tách
            // nét thoải mái vẫn qua được, còn phần lệch do nét thừa đã bị chính điểm số phạt.
            if (k > n * 2 + MAX_MERGE_ALLOWANCE) continue;
            Scored best = score(ref, user, 0);
            // Bản vẽ gần đủ nét (hoặc vẽ liền một nét): thử thêm phương án co giãn/dịch khung để bù
            // trường hợp vẽ nhỏ, vẽ lệch góc canvas.
            if (k == 1 || k * 10 >= n * 7) {
                double[][] fitted = fit(user, userBounds, ref.bounds());
                if (fitted != null) {
                    Scored alt = score(ref, fitted, FIT_PENALTY);
                    if (alt.score() < best.score()) best = alt;
                }
            }
            scored.add(best);
            candidates.add(ref);
        }

        double[][] userInk = input.stream().map(StrokeMatcher::flatten).toArray(double[][]::new);
        double[][] userInkGeometry = densify(input);
        double[] inkBounds = bounds(userInk);
        double userInkLength = inkLength(userInk);
        BitSet userInkMask = inkMask(userInkGeometry);
        byte[] userDistanceMap = inkDistanceMap(userInkGeometry);
        int userStrokeCount = user.length;
        boolean likelySplitBoundaries = hasLikelySplitBoundaries(input);
        boolean closedInk = hasClosedInk(input);
        int maxMergedReferenceStrokes = userStrokeCount == 1 ? 8 : userStrokeCount * 2;
        int bestStrokeCandidate = -1;
        for (int i = 0; i < scored.size(); i++) {
            if (scored.get(i).meanCost() <= MAX_MEAN_COST
                    && (bestStrokeCandidate < 0
                    || scored.get(i).score() < scored.get(bestStrokeCandidate).score())) {
                bestStrokeCandidate = i;
            }
        }
        boolean strongStrokeMatch = bestStrokeCandidate >= 0
                && scored.get(bestStrokeCandidate).meanCost() <= INK_RESCUE_MEAN_COST;
        if (strongStrokeMatch && !likelySplitBoundaries) return scored;
        List<Integer> mergedCandidates = new ArrayList<>();
        List<Integer> splitCandidates = new ArrayList<>();
        double[] rawCoverage = new double[scored.size()];
        java.util.Arrays.fill(rawCoverage, Double.MAX_VALUE);
        for (int i = 0; i < scored.size(); i++) {
            int referenceStrokeCount = candidates.get(i).strokes().length;
            boolean merged = referenceStrokeCount * 2 >= userStrokeCount * 3
                    && referenceStrokeCount <= maxMergedReferenceStrokes;
            boolean split = likelySplitBoundaries && userStrokeCount > referenceStrokeCount;
            if ((split || (merged && scored.get(i).meanCost() > INK_RESCUE_MEAN_COST))
                    && inkCouldMatch(inkBounds, userInkLength, candidates.get(i))) {
                rawCoverage[i] = inkCoverageError(
                        userInk, userInkMask, candidates.get(i).strokes(), candidates.get(i).inkMask());
                if (merged) mergedCandidates.add(i);
                else splitCandidates.add(i);
            }
        }
        // Nhánh gộp giữ đúng dải tỷ lệ đã được kiểm chứng trên corpus. Nhánh tách có thể chứa nhiều chữ ít nét,
        // nên lấy shortlist kép theo điểm nét và độ phủ mực để chi phí không tăng theo toàn bộ index.
        boolean[] selected = new boolean[scored.size()];
        boolean[] mergedSelection = new boolean[scored.size()];
        for (int i : mergedCandidates) {
            selected[i] = true;
            mergedSelection[i] = true;
        }
        splitCandidates.sort(Comparator.comparingDouble(i -> scored.get(i).score()));
        for (int position = 0; position < Math.min(INK_RESCUE_CANDIDATES_PER_SIGNAL, splitCandidates.size()); position++) {
            selected[splitCandidates.get(position)] = true;
        }
        splitCandidates.sort(Comparator.comparingDouble(i -> rawCoverage[i]));
        for (int position = 0; position < Math.min(INK_RESCUE_CANDIDATES_PER_SIGNAL, splitCandidates.size()); position++) {
            selected[splitCandidates.get(position)] = true;
        }
        for (int i = 0; i < selected.length; i++) {
            if (!selected[i]) continue;
            Ref ref = candidates.get(i);
            Scored best = scored.get(i);
            // Khi phép ghép từng nét không giải thích tốt bản vẽ, chấm thêm toàn bộ đường mực theo hình học.
            // Cách này không phụ thuộc số lần nhấc bút, nên xử lý đối xứng cả nét bị gộp lẫn nét bị tách.
            double cover = rawCoverage[i];
            InkScore ink = scoreInk(cover, userInk, userDistanceMap, ref);
            double inkPenalty = 0;
            double coverageThreshold = mergedSelection[i] && !closedInk
                    ? MAX_MERGED_INK_COVERAGE_ERROR : MAX_INK_COVERAGE_ERROR;
            // Bộ lọc khung bao/độ dài ở trên đã xác nhận ứng viên còn có thể khớp. Nếu kích thước hoặc vị trí
            // canvas làm độ phủ thô trượt ngưỡng, chuẩn hoá khung rồi chấm lại thay vì loại sớm.
            double fittedRetryLimit = mergedSelection[i] && !closedInk
                    ? MAX_FITTED_RETRY_ERROR : MAX_SHORT_DRAWING_FITTED_RETRY_ERROR;
            boolean allowFittedRetry = !mergedSelection[i] || closedInk || userStrokeCount <= 4;
            double[][] fitted = allowFittedRetry && ink.coverageError() > coverageThreshold
                    && ink.coverageError() <= fittedRetryLimit
                    ? (closedInk || !mergedSelection[i]
                            ? fitInk(userInk, inkBounds, ref.bounds())
                            : fit(userInk, inkBounds, ref.bounds()))
                    : null;
            if (fitted != null) {
                double[][] fittedGeometry = closedInk || !mergedSelection[i]
                        ? fitInk(userInkGeometry, inkBounds, ref.bounds())
                        : fit(userInkGeometry, inkBounds, ref.bounds());
                double fittedCover = inkCoverageError(fitted, inkMask(fittedGeometry),
                        ref.strokes(), ref.inkMask());
                InkScore fittedInk = scoreInk(fittedCover, fitted, inkDistanceMap(fittedGeometry), ref);
                boolean fittedPasses = fittedInk.coverageError() <= coverageThreshold;
                boolean rawPasses = ink.coverageError() <= coverageThreshold;
                if ((fittedPasses && !rawPasses) || (fittedPasses == rawPasses
                        && fittedInk.meanDistance() + FIT_PENALTY * FRAME < ink.meanDistance())) {
                    ink = fittedInk;
                    inkPenalty = FIT_PENALTY;
                }
            }
            if (ink.coverageError() <= coverageThreshold) {
                // Phần đuôi khoảng cách chặn mực thừa/thiếu bị trung bình toàn chữ che lấp. Nhánh tách giữ thêm
                // một phần điểm ghép nét để chữ ít nét không chen lên chỉ nhờ có đường mực gần giống.
                double rescue = (ink.meanDistance() + INK_TAIL_WEIGHT * ink.tailDistance()) / FRAME
                        + inkPenalty
                        + (mergedSelection[i] ? 0 : INK_STROKE_SCORE_WEIGHT * best.score());
                if (rescue <= MAX_INK_RESCUE_SCORE && rescue < best.score()) {
                    best = new Scored(ref.character(), rescue, best.meanCost(), true);
                }
            }
            scored.set(i, best);
        }
        return scored;
    }

    /** Nhiều ranh giới có hai đầu gần trùng nhau là dấu hiệu một nét liền đã bị nhấc bút giữa chừng. */
    private static boolean hasLikelySplitBoundaries(List<List<double[]>> input) {
        if (input.size() < 2) return false;
        int nearby = 0;
        for (int i = 1; i < input.size(); i++) {
            List<double[]> previous = input.get(i - 1);
            List<double[]> current = input.get(i);
            if (previous.isEmpty() || current.isEmpty()) continue;
            double[] end = previous.get(previous.size() - 1);
            double[] start = current.get(0);
            double gap = Math.hypot(end[0] - start[0], end[1] - start[1]);
            if (gap <= 2 * INK_TOLERANCE) nearby++;
        }
        return nearby * 2 >= input.size() - 1;
    }

    /** Đường kín có mọi đầu nét nối được với một đầu nét khác; cho phép chuẩn hoá hai trục an toàn. */
    private static boolean hasClosedInk(List<List<double[]>> input) {
        List<double[]> endpoints = new ArrayList<>(input.size() * 2);
        for (List<double[]> stroke : input) {
            if (stroke.isEmpty()) continue;
            endpoints.add(stroke.get(0));
            endpoints.add(stroke.get(stroke.size() - 1));
        }
        if (endpoints.size() < 2) return false;
        for (int i = 0; i < endpoints.size(); i++) {
            boolean joined = false;
            for (int j = 0; j < endpoints.size(); j++) {
                if (i == j) continue;
                if (Math.hypot(endpoints.get(i)[0] - endpoints.get(j)[0],
                        endpoints.get(i)[1] - endpoints.get(j)[1]) <= 2 * INK_TOLERANCE) {
                    joined = true;
                    break;
                }
            }
            if (!joined) return false;
        }
        return true;
    }

    /** Bộ lọc thô cho đường cứu hộ: khung bao và lượng mực có thể khớp nhau không. */
    private static boolean inkCouldMatch(double[] userBounds, double userLength, Ref ref) {
        double userSize = Math.max(userBounds[2] - userBounds[0], userBounds[3] - userBounds[1]);
        double refSize = Math.max(ref.bounds()[2] - ref.bounds()[0], ref.bounds()[3] - ref.bounds()[1]);
        if (userSize < 1e-6 || refSize < 1e-6) return false;
        double scale = refSize / userSize;
        if (scale < MIN_FIT_SCALE || scale > MAX_FIT_SCALE) return false;
        double widthError = Math.abs((userBounds[2] - userBounds[0]) * scale
                - (ref.bounds()[2] - ref.bounds()[0]));
        double heightError = Math.abs((userBounds[3] - userBounds[1]) * scale
                - (ref.bounds()[3] - ref.bounds()[1]));
        if (widthError > BOX_TOLERANCE || heightError > BOX_TOLERANCE) return false;
        double fittedLength = userLength * scale;
        return fittedLength >= ref.inkLength() * 0.4 && fittedLength <= ref.inkLength() * 2.5;
    }

    private static Scored score(Ref reference, double[][] user, double bonusPenalty) {
        double[][] ref = reference.strokes();
        int k = user.length, n = ref.length;
        double[][] cost = new double[k][n];
        // geometry[][] là khoảng cách hình học THUẦN, không cộng phí chiều nét/thứ tự nét. Phí đó chỉ
        // để xếp hạng; nếu để nó vào meanCost thì nét vẽ ngược chiều bị coi là "khớp kém" và kích hoạt
        // nhầm đường cứu hộ, khiến ứng viên khác chen lên.
        double[][] geometry = new double[k][n];
        double[] pair = new double[2];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < n; j++) {
                strokeDistances(user[i], ref[j], pair);
                geometry[i][j] = pair[0] / FRAME;
                cost[i][j] = pair[1] / FRAME + ORDER_WEIGHT * Math.abs(i - j);
            }
        }
        boolean[] usedRef = new boolean[n];
        boolean[] usedUser = new boolean[k];
        double matchedCost = 0;
        double matchedGeometry = 0;
        int matched = Math.min(k, n);
        for (int round = 0; round < matched; round++) {
            int bi = -1, bj = -1;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < k; i++) {
                if (usedUser[i]) continue;
                for (int j = 0; j < n; j++) {
                    if (!usedRef[j] && cost[i][j] < best) {
                        best = cost[i][j];
                        bi = i;
                        bj = j;
                    }
                }
            }
            usedUser[bi] = true;
            usedRef[bj] = true;
            matchedCost += best;
            matchedGeometry += geometry[bi][bj];
        }
        double extraInk = 0;
        for (int i = 0; i < k; i++) {
            if (!usedUser[i]) extraInk += extraStrokePenalty(user[i], reference.inkDistanceMap());
        }
        double total = matchedCost + (n - matched) * MISSING_PENALTY + extraInk + bonusPenalty;
        return new Scored(reference.character(), total, matchedGeometry / matched, false);
    }

    /**
     * Phí của một nét vẽ không ghép được với nét chuẩn nào: tỷ lệ với khoảng cách trung bình từ nét đó
     * tới mực của chữ. Nét nằm đè lên chữ (do tách nét) gần như miễn phí; nét vạch ra ngoài thì rất đắt.
     */
    private static double extraStrokePenalty(double[] stroke, byte[] referenceInk) {
        double sum = 0;
        for (int p = 0; p < POINTS; p++) sum += gridDistance(referenceInk, stroke[2 * p], stroke[2 * p + 1]);
        return EXTRA_STROKE_FLOOR + EXTRA_INK_WEIGHT * (sum / POINTS) / FRAME;
    }

    /**
     * Sai lệch phủ mực hai chiều: phần mực người dùng không được chữ giải thích, hoặc phần mực chuẩn
     * chưa được vẽ — lấy chiều tệ hơn. Dùng làm ngưỡng loại nét rác cho đường cứu hộ.
     */
    private static double inkCoverageError(double[][] user, BitSet userMask, double[][] ref, BitSet refMask) {
        int userCovered = 0, refCovered = 0;
        for (double[] stroke : user) {
            for (int p = 0; p < stroke.length; p += 2) {
                if (covered(refMask, stroke[p], stroke[p + 1])) userCovered++;
            }
        }
        for (double[] stroke : ref) {
            for (int p = 0; p < stroke.length; p += 2) {
                if (covered(userMask, stroke[p], stroke[p + 1])) refCovered++;
            }
        }
        // Chiều nào tệ hơn thì lấy chiều đó: mực người dùng không giải thích được, hoặc mực chuẩn chưa vẽ.
        return Math.max(1 - (double) userCovered / pointCount(user), 1 - (double) refCovered / pointCount(ref));
    }

    private static double inkMeanDistance(double[][] user, byte[] userMap, double[][] ref, byte[] refMap) {
        double[] directional = inkDirectionalMeanDistance(user, userMap, ref, refMap);
        return (directional[0] + directional[1]) / 2;
    }

    private static double[] inkDirectionalMeanDistance(double[][] user, byte[] userMap,
                                                        double[][] ref, byte[] refMap) {
        double userDistance = 0, refDistance = 0;
        for (double[] stroke : user) {
            for (int p = 0; p < stroke.length; p += 2) {
                userDistance += gridDistance(refMap, stroke[p], stroke[p + 1]);
            }
        }
        for (double[] stroke : ref) {
            for (int p = 0; p < stroke.length; p += 2) {
                refDistance += gridDistance(userMap, stroke[p], stroke[p + 1]);
            }
        }
        int userPoints = pointCount(user), refPoints = pointCount(ref);
        return new double[]{userDistance / userPoints, refDistance / refPoints};
    }

    /** Trung bình phần khoảng cách tệ nhất; phát hiện mực thừa hoặc thiếu mà trung bình toàn chữ che lấp. */
    private static double inkTailMeanDistance(double[][] ink, byte[] otherMap, double fraction) {
        double[] distances = new double[pointCount(ink)];
        int index = 0;
        for (double[] stroke : ink) {
            for (int p = 0; p < stroke.length; p += 2) {
                distances[index++] = gridDistance(otherMap, stroke[p], stroke[p + 1]);
            }
        }
        java.util.Arrays.sort(distances);
        int first = Math.max(0, distances.length - Math.max(1, (int) Math.ceil(distances.length * fraction)));
        double sum = 0;
        for (int i = first; i < distances.length; i++) sum += distances[i];
        return sum / (distances.length - first);
    }

    private static byte[] inkDistanceMap(double[][] ink) {
        int[] distances = new int[INK_GRID * INK_GRID];
        java.util.Arrays.fill(distances, 255);
        for (double[] stroke : ink) {
            for (int p = 0; p < stroke.length; p += 2) {
                int x = (int) Math.round(stroke[p]), y = (int) Math.round(stroke[p + 1]);
                if (x >= 0 && x < INK_GRID && y >= 0 && y < INK_GRID) distances[y * INK_GRID + x] = 0;
            }
        }
        for (int y = 0; y < INK_GRID; y++) {
            for (int x = 0; x < INK_GRID; x++) {
                int i = y * INK_GRID + x;
                if (x > 0) distances[i] = Math.min(distances[i], distances[i - 1] + 2);
                if (y > 0) distances[i] = Math.min(distances[i], distances[i - INK_GRID] + 2);
                if (x > 0 && y > 0) distances[i] = Math.min(distances[i], distances[i - INK_GRID - 1] + 3);
                if (x + 1 < INK_GRID && y > 0) distances[i] = Math.min(distances[i], distances[i - INK_GRID + 1] + 3);
            }
        }
        for (int y = INK_GRID - 1; y >= 0; y--) {
            for (int x = INK_GRID - 1; x >= 0; x--) {
                int i = y * INK_GRID + x;
                if (x + 1 < INK_GRID) distances[i] = Math.min(distances[i], distances[i + 1] + 2);
                if (y + 1 < INK_GRID) distances[i] = Math.min(distances[i], distances[i + INK_GRID] + 2);
                if (x + 1 < INK_GRID && y + 1 < INK_GRID) {
                    distances[i] = Math.min(distances[i], distances[i + INK_GRID + 1] + 3);
                }
                if (x > 0 && y + 1 < INK_GRID) {
                    distances[i] = Math.min(distances[i], distances[i + INK_GRID - 1] + 3);
                }
            }
        }
        byte[] map = new byte[distances.length];
        for (int i = 0; i < distances.length; i++) map[i] = (byte) Math.min(255, distances[i]);
        return map;
    }

    private static double gridDistance(byte[] map, double x, double y) {
        int px = Math.max(0, Math.min(INK_GRID - 1, (int) Math.round(x)));
        int py = Math.max(0, Math.min(INK_GRID - 1, (int) Math.round(y)));
        double outside = Math.hypot(x - Math.max(0, Math.min(INK_GRID - 1, x)),
                y - Math.max(0, Math.min(INK_GRID - 1, y)));
        return Byte.toUnsignedInt(map[py * INK_GRID + px]) / 2.0 + outside;
    }

    private static BitSet inkMask(double[][] ink) {
        BitSet mask = new BitSet(INK_GRID * INK_GRID);
        double toleranceSq = INK_TOLERANCE * INK_TOLERANCE;
        for (double[] stroke : ink) {
            for (int p = 0; p < stroke.length; p += 2) {
                double px = stroke[p], py = stroke[p + 1];
                int minX = Math.max(0, (int) Math.ceil(px - INK_TOLERANCE));
                int maxX = Math.min(INK_GRID - 1, (int) Math.floor(px + INK_TOLERANCE));
                int minY = Math.max(0, (int) Math.ceil(py - INK_TOLERANCE));
                int maxY = Math.min(INK_GRID - 1, (int) Math.floor(py + INK_TOLERANCE));
                for (int y = minY; y <= maxY; y++) {
                    for (int x = minX; x <= maxX; x++) {
                        if ((x - px) * (x - px) + (y - py) * (y - py) <= toleranceSq) {
                            mask.set(y * INK_GRID + x);
                        }
                    }
                }
            }
        }
        return mask;
    }

    private static boolean covered(BitSet mask, double x, double y) {
        int px = (int) Math.round(x), py = (int) Math.round(y);
        return px >= 0 && px < INK_GRID && py >= 0 && py < INK_GRID && mask.get(py * INK_GRID + px);
    }

    private static int pointCount(double[][] ink) {
        int points = 0;
        for (double[] stroke : ink) points += stroke.length / 2;
        return points;
    }

    private static double inkLength(double[][] ink) {
        double length = 0;
        for (double[] stroke : ink) {
            for (int p = 2; p < stroke.length; p += 2) {
                length += Math.hypot(stroke[p] - stroke[p - 2], stroke[p + 1] - stroke[p - 1]);
            }
        }
        return length;
    }

    /** Samples submitted polylines by arc length so ink geometry does not depend on browser vertex density. */
    private static double[][] densify(List<List<double[]>> lines) {
        double[] lengths = new double[lines.size()];
        int[] counts = new int[lines.size()];
        double totalLength = 0;
        int totalPoints = 0;
        for (int i = 0; i < lines.size(); i++) {
            List<double[]> line = lines.get(i);
            for (int p = 1; p < line.size(); p++) {
                lengths[i] += Math.hypot(line.get(p)[0] - line.get(p - 1)[0],
                        line.get(p)[1] - line.get(p - 1)[1]);
            }
            totalLength += lengths[i];
            counts[i] = lengths[i] == 0 ? 1 : (int) Math.ceil(lengths[i] / INK_POINT_SPACING) + 1;
            totalPoints += counts[i];
        }
        if (totalPoints > MAX_USER_INK_POINTS) {
            int remaining = MAX_USER_INK_POINTS;
            for (int i = 0; i < counts.length; i++) {
                counts[i] = lengths[i] == 0 ? 1 : 2;
                remaining -= counts[i];
            }
            for (int i = 0; i < counts.length && remaining > 0; i++) {
                int extra = (int) Math.floor(remaining * lengths[i] / totalLength);
                counts[i] += extra;
                remaining -= extra;
                totalLength -= lengths[i];
            }
            for (int i = 0; remaining > 0; i = (i + 1) % counts.length) {
                if (lengths[i] > 0) {
                    counts[i]++;
                    remaining--;
                }
            }
        }
        double[][] out = new double[lines.size()][];
        for (int i = 0; i < lines.size(); i++) out[i] = resample(lines.get(i), counts[i]);
        return out;
    }

    private static double[] flatten(List<double[]> line) {
        double[] out = new double[line.size() * 2];
        for (int i = 0; i < line.size(); i++) {
            out[2 * i] = line.get(i)[0];
            out[2 * i + 1] = line.get(i)[1];
        }
        return out;
    }

    /**
     * Khoảng cách trung bình giữa hai nét đã lấy mẫu. Người mới học hay vẽ ngược chiều nét nên xét
     * cả chiều đảo, chỉ cộng phạt nhẹ để chiều đúng vẫn được ưu tiên khi hai chiều gần như bằng nhau.
     */
    /**
     * Một lượt duy nhất cho cả hai số đo: {@code out[0]} là khoảng cách hình học thuần (dùng làm độ
     * tin cậy), {@code out[1]} cộng thêm phí vẽ ngược chiều (dùng để xếp hạng).
     */
    private static void strokeDistances(double[] a, double[] b, double[] out) {
        double forward = 0, reversed = 0;
        for (int p = 0; p < POINTS; p++) {
            int q = POINTS - 1 - p;
            double ax = a[2 * p], ay = a[2 * p + 1];
            double fx = ax - b[2 * p], fy = ay - b[2 * p + 1];
            double rx = ax - b[2 * q], ry = ay - b[2 * q + 1];
            forward += Math.sqrt(fx * fx + fy * fy);
            reversed += Math.sqrt(rx * rx + ry * ry);
        }
        out[0] = Math.min(forward, reversed) / POINTS;
        out[1] = Math.min(forward, reversed + REVERSE_PENALTY * FRAME * POINTS) / POINTS;
    }

    /** Co giãn đồng tỷ lệ và dịch bản vẽ để khung bao trùng với khung bao của chữ chuẩn. */
    private static double[][] fit(double[][] user, double[] from, double[] to) {
        double fromSize = Math.max(from[2] - from[0], from[3] - from[1]);
        double toSize = Math.max(to[2] - to[0], to[3] - to[1]);
        if (fromSize < 1e-6 || toSize < 1e-6) return null;
        double scale = Math.max(MIN_FIT_SCALE, Math.min(MAX_FIT_SCALE, toSize / fromSize));
        double fromX = (from[0] + from[2]) / 2, fromY = (from[1] + from[3]) / 2;
        double toX = (to[0] + to[2]) / 2, toY = (to[1] + to[3]) / 2;
        double[][] out = new double[user.length][];
        for (int i = 0; i < user.length; i++) {
            double[] s = user[i].clone();
            for (int p = 0; p < s.length; p += 2) {
                s[p] = (s[p] - fromX) * scale + toX;
                s[p + 1] = (s[p + 1] - fromY) * scale + toY;
            }
            out[i] = s;
        }
        return out;
    }

    /** Chuẩn hoá riêng hai trục cho so khớp toàn bộ đường mực; chữ viết tay thường hẹp/rộng hơn mẫu KanjiVG. */
    private static double[][] fitInk(double[][] user, double[] from, double[] to) {
        double fromWidth = from[2] - from[0], fromHeight = from[3] - from[1];
        double toWidth = to[2] - to[0], toHeight = to[3] - to[1];
        if (fromWidth < 1e-6 || fromHeight < 1e-6 || toWidth < 1e-6 || toHeight < 1e-6) return null;
        double scaleX = toWidth / fromWidth, scaleY = toHeight / fromHeight;
        if (scaleX < MIN_FIT_SCALE || scaleX > MAX_FIT_SCALE
                || scaleY < MIN_FIT_SCALE || scaleY > MAX_FIT_SCALE) return null;
        double fromX = (from[0] + from[2]) / 2, fromY = (from[1] + from[3]) / 2;
        double toX = (to[0] + to[2]) / 2, toY = (to[1] + to[3]) / 2;
        double[][] out = new double[user.length][];
        for (int i = 0; i < user.length; i++) {
            double[] stroke = user[i].clone();
            for (int p = 0; p < stroke.length; p += 2) {
                stroke[p] = (stroke[p] - fromX) * scaleX + toX;
                stroke[p + 1] = (stroke[p + 1] - fromY) * scaleY + toY;
            }
            out[i] = stroke;
        }
        return out;
    }

    private static double[] bounds(double[][] strokes) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] s : strokes) {
            for (int p = 0; p < s.length; p += 2) {
                minX = Math.min(minX, s[p]);
                maxX = Math.max(maxX, s[p]);
                minY = Math.min(minY, s[p + 1]);
                maxY = Math.max(maxY, s[p + 1]);
            }
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /** Lấy mẫu lại polyline thành đúng {@link #POINTS} điểm cách đều theo độ dài cung. */
    static double[] resample(List<double[]> line) {
        return resample(line, POINTS);
    }

    private static double[] resample(List<double[]> line, int points) {
        double[] out = new double[points * 2];
        int size = line.size();
        double[] cumulative = new double[size];
        for (int i = 1; i < size; i++) {
            cumulative[i] = cumulative[i - 1] + Math.hypot(
                    line.get(i)[0] - line.get(i - 1)[0], line.get(i)[1] - line.get(i - 1)[1]);
        }
        double length = cumulative[size - 1];
        int segment = 1;
        for (int p = 0; p < points; p++) {
            if (size == 1 || length == 0) { // nét chấm: mọi điểm trùng nhau
                out[2 * p] = line.get(0)[0];
                out[2 * p + 1] = line.get(0)[1];
                continue;
            }
            double target = points == 1 ? 0 : length * p / (points - 1);
            while (segment < size - 1 && cumulative[segment] < target) segment++;
            double span = cumulative[segment] - cumulative[segment - 1];
            double t = span == 0 ? 0 : (target - cumulative[segment - 1]) / span;
            double[] a = line.get(segment - 1), b = line.get(segment);
            out[2 * p] = a[0] + (b[0] - a[0]) * t;
            out[2 * p + 1] = a[1] + (b[1] - a[1]) * t;
        }
        return out;
    }
}
