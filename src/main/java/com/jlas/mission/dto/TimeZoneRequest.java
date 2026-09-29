package com.jlas.mission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** IANA zone reported by the browser, for example Asia/Ho_Chi_Minh. */
public record TimeZoneRequest(@NotBlank @Size(max = 64) String zoneId) {
}
