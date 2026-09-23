package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Textbook;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Repository cho {@link Textbook}. */
@Repository
public interface TextbookRepository extends JpaRepository<Textbook, Long> {

    List<Textbook> findAllByOrderByIdAsc();

    List<Textbook> findByStatusOrderByIdAsc(String status);

    Optional<Textbook> findByIdAndStatus(Long id, String status);

    /** JLPT curriculum textbooks (published, level in range); {@code id < idLessThan} excludes the local demo seed. */
    List<Textbook> findByJlptLevelInAndStatusAndIdLessThanOrderByIdAsc(
            List<String> jlptLevels, String status, Long idLessThan);

    /** Số unit theo textbook cho một tập textbook, 1 query; mỗi phần tử là [textbookId, count]. */
    @Query("""
            select u.textbook.id, count(u.id) from Unit u
            where u.textbook.id in :textbookIds
            group by u.textbook.id
            """)
    List<Object[]> countUnitsByTextbookIds(@Param("textbookIds") List<Long> textbookIds);
}
