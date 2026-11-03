package com.jlas.jlptpath.dto;

/** [JLAS-W0108] Title and text of one study reminder, the same for the web notification and the email. */
public record JpathReminderMessage(String title, String message) {
}
