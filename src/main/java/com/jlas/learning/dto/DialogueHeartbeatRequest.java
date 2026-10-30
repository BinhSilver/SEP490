package com.jlas.learning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** [JLAS-W0193] Task 7 — a visible-heartbeat signal. Deliberately carries no elapsed time, timestamp, total
 * seconds or completed flag: the server derives everything from its own clock and the previously stored
 * {@code lastHeartbeatAt}. {@code heartbeatToken} is a client-generated nonce used only to detect an exact
 * retry/replay of the same heartbeat so it is not credited twice. */
public record DialogueHeartbeatRequest(@NotBlank @Size(max = 80) String heartbeatToken) { }
