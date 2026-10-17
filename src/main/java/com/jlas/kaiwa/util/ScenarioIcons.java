package com.jlas.kaiwa.util;

import com.jlas.kaiwa.model.Scenario;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Presentation icons scoped by root scenario, since scene codes can be reused. */
public final class ScenarioIcons {
    private ScenarioIcons() { }
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String authoredIcon(Scenario scenario, String code) {
        if (scenario.getFlowJson() == null) return null;
        try {
            for (var state : JSON.readTree(scenario.getFlowJson()).path("states")) {
                boolean matches = code.equals(state.path("code").asText());
                for (var goal : state.path("allowedGoalCodes")) matches |= code.equals(goal.asText());
                if (matches && state.path("icon").asText().matches("fa-(solid|regular) fa-[a-z-]+"))
                    return state.path("icon").asText();
            }
        } catch (Exception ignored) { }
        return null;
    }

    private record IconSet(String topic, Map<String, String> steps) { }

    private static Map<String, String> steps(String... pairs) {
        Map<String, String> icons = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) icons.put(pairs[i], "fa-solid fa-" + pairs[i + 1]);
        return Map.copyOf(icons);
    }

    private static IconSet set(String topic, String... pairs) {
        return new IconSet("fa-solid fa-" + topic, steps(pairs));
    }

    private static final Map<String, IconSet> CATALOG = Map.ofEntries(
        Map.entry("restaurant-order-and-pay", set("utensils",
            "WAIT", "hourglass-half", "WAIT_TIME", "hourglass-half", "WAIT_CHOICE", "clock", "WAIT_DECISION", "clock",
            "SEATING", "chair", "MENU", "book-open", "SOLD_OUT", "ban", "INGREDIENTS", "carrot", "ASK_INGREDIENTS", "carrot",
            "RESTRICTION", "hand", "ADJUST", "sliders", "CONFIRM_ADJUST", "clipboard-check",
            "WRONG_DISH", "triangle-exclamation", "EXPLAIN_WRONG", "triangle-exclamation", "EXCHANGE", "arrows-rotate", "REQUEST_EXCHANGE", "arrows-rotate", "SERVED", "bowl-food", "RECEIVE", "bowl-food",
            "ADD_MORE", "circle-plus", "BILL", "receipt", "SPLIT", "divide", "OWN_SHARE", "user-check",
            "QUALITY_REPORT", "triangle-exclamation", "QUALITY_REQUEST", "sliders", "QUALITY_OPTIONS", "clock",
            "QUALITY_CONFIRM", "clipboard-check", "QUALITY_CHECK", "bowl-food",
            "END_SUCCESS", "circle-check", "END_LEAVE", "person-walking",
            "GREETING", "door-open", "ORDERING", "utensils", "CONFIRM", "clipboard-check", "PAYMENT", "receipt",
            "TABLE", "user-group", "ORDER", "utensils", "PAY", "wallet",
            "ASK_ALTERNATIVE", "circle-question", "CHOOSE", "list-check", "CONFIRM_ORDER", "clipboard-check",
            "EXPLAIN", "triangle-exclamation", "CHANGE", "arrows-rotate", "ACCEPT", "utensils")),
        Map.entry("first-meeting-introduction", set("user-group",
            "GREETING", "handshake", "NAME", "id-card", "ORIGIN", "earth-asia", "HOBBY", "heart",
            "SHARE", "heart", "ASK_BACK", "comments", "INVITE", "calendar-plus",
            "NAME_FIX", "id-card", "ORIGIN_FIX", "location-dot", "RECIPROCATE", "comments")),
        Map.entry("station-directions-and-transfer", set("train-subway",
            "DESTINATION", "location-dot", "LINE", "train-subway", "PLATFORM", "signs-post",
            "NEXT_TRAIN", "train", "ARRIVAL", "clock", "PLAN", "route",
            "CHECK_ROUTE", "compass", "TRANSFER", "shuffle", "VERIFY", "map-location-dot")),
        Map.entry("convenience-store-checkout", set("basket-shopping",
            "CHECKOUT", "cash-register", "HEATING", "fire-burner", "BAG_PAYMENT", "bag-shopping",
            "ITEM", "basket-shopping", "HEAT", "fire-burner", "BAG_PAY", "bag-shopping",
            "RETRY", "credit-card", "OTHER_PAYMENT", "money-bill-wave", "FINISH_PAY", "receipt",
            "DECLINE_HEAT", "temperature-low", "DECLINE_BAG", "bag-shopping", "ASK_CHOPSTICKS", "utensils")),
        Map.entry("clothes-shopping-size-fitting", set("shirt",
            "FINDING", "shirt", "ITEM", "shirt", "SIZE", "ruler", "FITTING", "person",
            "ASK_SIZE", "ruler", "TRY_OTHER", "shirt", "DECIDE", "bag-shopping",
            "EXPLAIN_SIZE", "ruler-combined", "SHOW_RECEIPT", "receipt", "CONFIRM_SIZE", "shirt")),
        Map.entry("clinic-appointment", set("calendar-plus",
            "SYMPTOM", "stethoscope", "DURATION", "clock", "BOOKING", "calendar-plus",
            "REQUEST_DAY", "calendar-day", "ALTERNATIVE_DAY", "calendar-days", "CONFIRM_BOOKING", "file-medical",
            "IDENTIFY_BOOKING", "address-card", "REQUEST_CHANGE", "calendar-days", "VERIFY_CHANGE", "calendar-check")),
        Map.entry("pharmacy-cold-medicine", set("pills",
            "SYMPTOM", "stethoscope", "REQUEST", "pills", "USAGE", "prescription-bottle-medical",
            "EXPLAIN_DRIVING", "car", "ASK_DROWSINESS", "bed", "SAFE_NEXT_STEP", "user-doctor",
            "DISCLOSE", "capsules", "ASK_COMBINATION", "prescription", "FOLLOW_CHECK", "user-doctor")),
        Map.entry("office-reschedule-meeting", set("calendar-days",
            "APOLOGY", "calendar-xmark", "PROPOSE", "calendar-plus", "CONFIRM", "clipboard-check",
            "REASON", "calendar-xmark", "COMMON_TIME", "clock", "NOTIFY", "bullhorn",
            "REQUEST_ONLINE", "video", "CHECK_TOOL", "link", "PREPARE", "file-lines")),
        Map.entry("school-absence-and-homework", set("graduation-cap",
            "IDENTIFY", "id-card", "ABSENCE", "thermometer", "HOMEWORK", "book-open",
            "EXPLAIN_ABSENCE", "thermometer", "EXTENSION", "calendar-plus", "CONFIRM_DEADLINE", "calendar-check",
            "RETURN", "school", "MISSED_LESSON", "book-open", "CATCH_UP", "pen-to-square")),
        Map.entry("hotel-check-in", set("hotel",
            "RESERVATION", "passport", "STAY", "calendar-days", "INFO", "circle-info",
            "SHOW_CODE", "ticket", "CHECK_DATES", "calendar-days", "CONFIRM_ROOM", "key",
            "REPORT", "faucet-drip", "REQUEST_HELP", "bell-concierge", "ACCEPT_SOLUTION", "key")),
        Map.entry("part-time-job-interview", set("user-tie",
            "MOTIVATION", "briefcase", "EXPERIENCE", "id-badge", "AVAILABILITY", "calendar-days",
            "HONEST", "user", "STRENGTH", "star", "LEARNING", "book-open",
            "CONSTRAINT", "calendar-xmark", "OFFER", "calendar-plus", "AGREEMENT", "handshake")),
        Map.entry("service-complaint-return", set("arrows-rotate",
            "ISSUE", "triangle-exclamation", "EVIDENCE", "receipt", "RESOLUTION", "arrows-rotate",
            "NO_RECEIPT", "file-circle-question", "ALTERNATE_PROOF", "credit-card", "PROCESS", "list-check",
            "REQUEST", "arrows-rotate", "OPTIONS", "list-ul", "CHOOSE", "handshake")),
        Map.entry("project-deadline-negotiation", set("list-check",
            "STATUS", "chart-line", "REMAINING", "list-check", "DELIVERY", "calendar-check",
            "RISK", "triangle-exclamation", "MITIGATION", "shield-halved", "NEW_DEADLINE", "calendar-plus",
            "CLARIFY_SCOPE", "file-circle-plus", "TRADEOFF", "scale-balanced", "PRIORITY", "bullseye")),
        Map.entry("rental-contract-negotiation", set("house",
            "TERMS", "file-contract", "FEES", "coins", "NEXT_ACTION", "clipboard-check",
            "DETAILS", "screwdriver-wrench", "VISIT", "calendar-days", "COST_NOTICE", "file-invoice-dollar",
            "MOVE_DATE", "truck-moving", "INSPECTION", "key", "SETTLEMENT", "file-invoice-dollar"))
    );

    private static IconSet lookup(Scenario scenario) {
        Scenario root = scenario.getParentScenario() == null ? scenario : scenario.getParentScenario();
        return CATALOG.get(root.getSlug());
    }

    public static String topic(Scenario scenario) {
        IconSet icons = lookup(scenario);
        return icons != null ? icons.topic() : "fa-solid fa-comments";
    }

    public static String step(Scenario scenario, String code) {
        String authored = authoredIcon(scenario, code);
        if (authored != null) return authored;
        IconSet icons = lookup(scenario);
        return icons != null ? icons.steps().getOrDefault(code, icons.topic()) : "fa-solid fa-comment-dots";
    }

    public static boolean hasStep(Scenario scenario, String code) {
        IconSet icons = lookup(scenario);
        return authoredIcon(scenario, code) != null || (icons != null && icons.steps().containsKey(code));
    }
}
