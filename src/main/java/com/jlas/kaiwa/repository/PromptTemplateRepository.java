package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.PromptTemplate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, Long> {
    Optional<PromptTemplate> findFirstByActiveTrueOrderByIdAsc();

    Optional<PromptTemplate> findFirstByName(String name);
}
