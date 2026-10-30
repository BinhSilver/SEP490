package com.jlas.conjugation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerbPracticeCatalogContextRepository extends JpaRepository<VerbPracticeCatalogContext, String> {
    List<VerbPracticeCatalogContext> findAllByOrderByIdAsc();
}
