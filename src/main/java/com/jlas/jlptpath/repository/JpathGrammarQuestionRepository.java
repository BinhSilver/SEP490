package com.jlas.jlptpath.repository;

import com.jlas.jlptpath.entity.JpathGrammarQuestion;
import com.jlas.jlptpath.enums.JpathContentStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** [JLAS-W0108] Grammar questions. */
@Repository
public interface JpathGrammarQuestionRepository extends JpaRepository<JpathGrammarQuestion, String> {

    /** Learner-visible questions of one pattern and bank: status in {@code statuses}, not hidden, by sort order. */
    List<JpathGrammarQuestion> findByGrammarCodeAndBankAndStatusInAndReportHiddenFalseOrderBySortOrder(
            String grammarCode, String bank, Collection<JpathContentStatus> statuses);

    /** {@code [grammarCode, count]} of the learner-visible questions in {@code bank} for each pattern of a level. */
    @Query("""
            select q.grammarCode, count(q) from JpathGrammarQuestion q
            where q.level = :level and q.bank = :bank and q.status in :statuses and q.reportHidden = false
            group by q.grammarCode
            """)
    List<Object[]> countVisibleByGrammar(@Param("level") String level, @Param("bank") String bank,
                                         @Param("statuses") Collection<JpathContentStatus> statuses);
}
