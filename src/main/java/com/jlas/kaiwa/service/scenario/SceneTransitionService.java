package com.jlas.kaiwa.service.scenario;

import com.jlas.kaiwa.model.ConversationState;
import com.jlas.kaiwa.model.Scenario;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Builds the neutral narration displayed between role-play scenes. */
@Service
public class SceneTransitionService {

    /* Also supports databases seeded before transitionText was added to flow_json. */
    private static final Map<String, String> FALLBACKS = Map.ofEntries(
            Map.entry("restaurant-order-and-pay:ORDERING", "Sau khi được dẫn tới bàn…"),
            Map.entry("restaurant-order-and-pay:CONFIRM", "Sau khi bạn chọn món…"),
            Map.entry("restaurant-order-and-pay:PAYMENT", "Sau khi dùng bữa…"),
            Map.entry("first-meeting-introduction:ORIGIN", "Sau phần chào hỏi ban đầu…"),
            Map.entry("first-meeting-introduction:HOBBY", "Khi cuộc trò chuyện trở nên thoải mái hơn…"),
            Map.entry("station-directions-and-transfer:LINE", "Sau khi bạn nêu điểm đến…"),
            Map.entry("station-directions-and-transfer:PLATFORM", "Sau khi xác nhận tuyến tàu…"),
            Map.entry("convenience-store-checkout:HEATING", "Tại quầy thanh toán…"),
            Map.entry("convenience-store-checkout:BAG_PAYMENT", "Sau khi nhân viên chuẩn bị món ăn…"),
            Map.entry("clothes-shopping-size-fitting:SIZE", "Sau khi tìm được mẫu áo phù hợp…"),
            Map.entry("clothes-shopping-size-fitting:FITTING", "Một lát sau, tại phòng thử đồ…"),
            Map.entry("clinic-appointment:DURATION", "Trong lúc làm thủ tục tiếp nhận…"),
            Map.entry("clinic-appointment:BOOKING", "Sau khi cung cấp thông tin triệu chứng…"),
            Map.entry("pharmacy-cold-medicine:REQUEST", "Sau khi mô tả triệu chứng…"),
            Map.entry("pharmacy-cold-medicine:USAGE", "Sau khi chọn được loại thuốc phù hợp…"),
            Map.entry("office-reschedule-meeting:PROPOSE", "Sau khi trình bày việc trùng lịch…"),
            Map.entry("office-reschedule-meeting:CONFIRM", "Sau khi thống nhất giờ họp mới…"),
            Map.entry("school-absence-and-homework:ABSENCE", "Sau khi xác nhận tên và lớp…"),
            Map.entry("school-absence-and-homework:HOMEWORK", "Sau khi giáo viên đồng ý cho nghỉ…"),
            Map.entry("hotel-check-in:STAY", "Sau khi tìm thấy thông tin đặt phòng…"),
            Map.entry("hotel-check-in:INFO", "Sau khi hoàn tất thủ tục nhận phòng…")
    );

    public String describe(Scenario scenario, ConversationState from, ConversationState to) {
        if (scenario == null || to == null || sameState(from, to) || "END".equalsIgnoreCase(to.getCode())) {
            return null;
        }
        if (to.getTransitionText() != null && !to.getTransitionText().isBlank()) {
            return to.getTransitionText().trim();
        }
        String key = (scenario.getSlug() == null ? "" : scenario.getSlug()) + ":" + to.getCode();
        String configured = FALLBACKS.get(key);
        if (configured != null) return configured;
        return to.getName() == null || to.getName().isBlank() ? null : "Chuyển sang cảnh: " + to.getName();
    }

    private static boolean sameState(ConversationState left, ConversationState right) {
        if (left == null || right == null) return left == right;
        if (left.getCode() != null || right.getCode() != null) {
            return Objects.equals(left.getCode(), right.getCode());
        }
        return Objects.equals(left.getName(), right.getName());
    }
}
