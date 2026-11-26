package com.jlas.jlptpath.dto;

/**
 * [JLAS-W0108] F238: skill sessions (Đọc · Nghe) carried from earlier weeks.
 *
 * @param owed       carried sessions still owed
 * @param skill      READING or LISTENING, the one to do next
 * @param offerToday today is a normal study day that may take one carried session now
 * @param doneToday  today's carried session is done
 */
public record JpathSkillCarryView(int owed, String skill, boolean offerToday, boolean doneToday) {
}
