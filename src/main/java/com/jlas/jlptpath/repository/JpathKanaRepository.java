package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathKana;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Kana characters. */
@Repository
public interface JpathKanaRepository extends JpaRepository<JpathKana, String> {

    /** Kana of one script (HIRAGANA or KATAKANA) in teaching order. */
    List<JpathKana> findByScriptOrderBySortOrder(String script);
}
