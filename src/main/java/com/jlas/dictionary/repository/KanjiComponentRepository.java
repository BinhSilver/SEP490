package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.KanjiComponent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository cho tra Kanji theo bộ thủ. "Kanji chứa mọi bộ phận đã chọn" = các kanji_id xuất hiện
 * với đủ {@code :count} bộ phận trong kanji_component_map (index idx_kanji_component_map_component).
 */
@Repository
public interface KanjiComponentRepository extends JpaRepository<KanjiComponent, Short> {

    @Query("select c from KanjiComponent c left join fetch c.radical order by c.strokeCount, c.displayOrder")
    List<KanjiComponent> findAllForPicker();

    @Query(value = "select count(*) from (select m.kanji_id from kanji_component_map m "
            + "where m.component_id in (:ids) group by m.kanji_id having count(*) = :count) matched",
            nativeQuery = true)
    long countKanjiWithAll(@Param("ids") List<Short> componentIds, @Param("count") int count);

    /** Kanji khớp, ít nét trước; cùng số nét thì Kanji xuất hiện trong nhiều từ vựng hơn lên trước. */
    @Query(value = "select k.`character` as kanjiCharacter, k.stroke_count as strokeCount, "
            + "k.han_viet as hanViet, k.jlpt_level as jlptLevel from kanji k "
            + "join (select m.kanji_id from kanji_component_map m where m.component_id in (:ids) "
            + "group by m.kanji_id having count(*) = :count) matched on matched.kanji_id = k.id "
            + "order by k.stroke_count is null, k.stroke_count, "
            + "(select count(*) from vocabulary_kanji vk where vk.kanji_id = k.id) desc, k.id "
            + "limit :limit", nativeQuery = true)
    List<ComponentKanjiRow> findKanjiWithAll(@Param("ids") List<Short> componentIds,
                                             @Param("count") int count, @Param("limit") int limit);

    /** Bộ phận còn xuất hiện trong ít nhất một Kanji khớp (gồm cả bộ phận đã chọn). */
    @Query(value = "select distinct m2.component_id from kanji_component_map m2 where m2.kanji_id in "
            + "(select m.kanji_id from kanji_component_map m where m.component_id in (:ids) "
            + "group by m.kanji_id having count(*) = :count)", nativeQuery = true)
    List<Number> findCooccurringComponentIds(@Param("ids") List<Short> componentIds, @Param("count") int count);

    interface ComponentKanjiRow {
        String getKanjiCharacter();
        Integer getStrokeCount();
        String getHanViet();
        String getJlptLevel();
    }
}
