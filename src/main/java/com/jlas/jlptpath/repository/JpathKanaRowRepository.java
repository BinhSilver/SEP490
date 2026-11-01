package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathKanaRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Kana rows. */
@Repository
public interface JpathKanaRowRepository extends JpaRepository<JpathKanaRow, String> {
}
