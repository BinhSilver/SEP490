package com.jlas.moderation.service;

import com.jlas.moderation.dto.ContributionDetail;
import java.util.Optional;

/** Implemented by the dictionary module so moderation can show a contribution without owning its tables. */
public interface ContributionDetailPort {
    Optional<ContributionDetail> detail(Long vocabularyId);
}
