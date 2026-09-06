package com.jlas.game.service.impl;

import com.jlas.game.service.GameService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cài đặt {@link GameService}. */
@Service
@RequiredArgsConstructor
@Transactional
public class GameServiceImpl implements GameService { }
