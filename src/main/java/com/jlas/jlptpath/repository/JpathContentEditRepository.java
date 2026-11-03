package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathContentEdit;
import com.jlas.jlptpath.enums.JpathReportTarget;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Content edit log. */
@Repository
public interface JpathContentEditRepository extends JpaRepository<JpathContentEdit, Long> {

    /** Newest edits of a target first. */
    List<JpathContentEdit> findByTargetTypeAndTargetCodeOrderByIdDesc(JpathReportTarget targetType, String targetCode,
                                                                     Pageable page);

    /** Edits of a level not yet exported, oldest first. */
    List<JpathContentEdit> findByLevelAndExportedAtIsNullOrderByIdAsc(String level);
}
