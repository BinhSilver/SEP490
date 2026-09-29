package com.jlas.learning.dto;

import java.util.List;

// Ordered textbook IDs resolved by the server's curriculum integration, not by Smart Test scores.
public record LearningCatalogScope(List<Long> warmUpTextbookIds, List<Long> goalTextbookIds) { }
