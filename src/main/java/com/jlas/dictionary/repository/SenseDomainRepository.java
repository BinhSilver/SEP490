package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.SenseDomain;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Repository cho {@link SenseDomain} (chuyên ngành của từng sense, ví dụ "Vật lý"). */
@Repository
public interface SenseDomainRepository extends JpaRepository<SenseDomain, SenseDomain.SenseDomainId> {

    @Query("select sd from SenseDomain sd join fetch sd.domain where sd.id.senseId in :senseIds")
    List<SenseDomain> findBySenseIdIn(@Param("senseIds") List<Long> senseIds);
}
