package com.jlas.mission.controller;

import com.jlas.mission.service.MissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Controller API nhiệm vụ & streak. Base path: /api/mission */
@RestController
@RequestMapping("/api/mission")
@RequiredArgsConstructor
public class MissionController {
    private final MissionService missionService;
}
