package com.jlas.assessment.service;

import java.util.List;

public interface JlptFuriganaService {
    List<String> annotate(List<String> texts);
}
