package com.jlas.shadowing.service.impl;

import com.jlas.shadowing.service.ShadowingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cài đặt {@link ShadowingService}. */
@Service
@RequiredArgsConstructor
@Transactional
public class ShadowingServiceImpl implements ShadowingService { }
