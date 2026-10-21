package com.jlas.conjugation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VerbPracticeCatalogVerbRepository extends JpaRepository<VerbPracticeCatalogVerb, String> {
    List<VerbPracticeCatalogVerb> findAllByOrderByIdAsc();
}
