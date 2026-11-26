package com.jlas.mypaths;

import com.jlas.notification.service.UserNotificationPort;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * [JLAS-W0108] Owner 24/11 "Lộ trình của tôi": the bell notifications about the learner's paths (all five approved:
 * added, paused / resumed, pace changed, 7 days without study, path finished). Best effort: a missing notification
 * module or a failure never breaks the action that caused it. Each event key is unique so a repeat is dropped.
 */
@Component
public class MyPathsNotifier {

    static final String TYPE = "LEARNING_PATHS";
    static final String DASHBOARD_URL = "/learning/my";
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ObjectProvider<UserNotificationPort> port;

    public MyPathsNotifier(ObjectProvider<UserNotificationPort> port) {
        this.port = port;
    }

    public void added(Long userId, String title, Integer totalMinutes, String stamp) {
        String total = totalMinutes == null ? "" : " Mỗi ngày tổng khoảng " + totalMinutes + " phút.";
        send(userId, "Đã thêm lộ trình", "Đã thêm " + title + "." + total, "mypaths-added-" + stamp);
    }

    public void paused(Long userId, String title, String stamp) {
        send(userId, "Đã tạm dừng lộ trình", "Đã tạm dừng " + title + ", tiến độ được giữ.", "mypaths-paused-" + stamp);
    }

    public void resumed(Long userId, String title, String where, String stamp) {
        send(userId, "Học tiếp lộ trình", title + " học tiếp" + (where == null ? "." : " từ " + where + "."), "mypaths-resumed-" + stamp);
    }

    public void paceChanged(Long userId, String title, LocalDate finish, String stamp) {
        if (finish == null) return;
        send(userId, "Đã đổi nhịp học", title + " dự kiến xong ngày " + VN_DATE.format(finish) + ".", "mypaths-pace-" + stamp);
    }

    /** Once per week and path (the key carries the Monday of the week). */
    public void idle(Long userId, String key, String title, LocalDate weekMonday) {
        send(userId, "Bạn chưa học " + title + " 7 ngày",
                "Bạn chưa học " + title + " 7 ngày. Học nhẹ hơn, tạm dừng hay giữ nguyên? Chọn trong Lộ trình của tôi.",
                "mypaths-idle-" + key + "-" + weekMonday);
    }

    /** Once per path. */
    public void finished(Long userId, String key, String title, String next) {
        send(userId, "Chúc mừng bạn đã học xong " + title,
                "Bạn đã học xong " + title + "." + (next == null ? "" : " Gợi ý tiếp theo: " + next + "."),
                "mypaths-finished-" + key);
    }

    private void send(Long userId, String title, String message, String eventKey) {
        try {
            UserNotificationPort p = port.getIfAvailable();
            if (p != null) p.notifyUser(userId, TYPE, title, message, DASHBOARD_URL, eventKey);
        } catch (RuntimeException e) {
            // best effort: the path action already happened
        }
    }
}
