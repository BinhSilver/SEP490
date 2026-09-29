package com.jlas.learning.service;
import com.jlas.learning.dto.InteractiveContentSnapshot;
public interface InteractiveContentCatalog { InteractiveContentSnapshot inspect(Long lessonId, String expectedLevel); }
