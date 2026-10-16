package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Kanji;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Repository cho {@link Kanji}. Cột {@code kanji.character} dùng collation {@code utf8mb4_bin}
 * (migration 005; unicode_ci coi mọi ký tự ngoài BMP là bằng nhau) nên so sánh trực tiếp vừa đúng
 * byte vừa dùng được index uk_kanji_character.
 */
@Repository
public interface KanjiRepository extends JpaRepository<Kanji, Long> {

    @Query(value = "select k.* from kanji k where k.`character` = :value limit 1",
            nativeQuery = true)
    Optional<Kanji> findByCharacter(@Param("value") String character);

    // IDE static SQL linter không hiểu cú pháp mở rộng list-parameter "IN (:values)" của Spring
    // Data JPA (nó expand thành (?,?,?...) lúc runtime) nên báo sai cú pháp ở đây — đã kiểm tra
    // đây là hạn chế của linter, không phải lỗi thật; đây là cách dùng chuẩn, có tài liệu của
    // Spring Data JPA cho native query với tham số List.
    @Query(value = "select k.* from kanji k where k.`character` in (:values)",
            nativeQuery = true)
    List<Kanji> findByCharacterIn(@Param("values") List<String> characters);

    /**
     * Kanji có một âm Hán Việt trùng với từ cần tìm, ví dụ "học" -> 学. Cột han_viet lưu dạng
     * "THỰC, TỰ"; so khớp đúng dấu, không phân biệt hoa/thường. Bảng kanji ~13 nghìn dòng, quét nhanh.
     */
    @Query(value = "select k.* from kanji k "
            + "where concat(', ', k.han_viet, ',') collate utf8mb4_0900_as_ci "
            + "like concat('%, ', :reading, ',%') "
            + "order by k.jlpt_level is null, k.jlpt_level desc, "
            // Bảng kanji có cả Kanji hiếm: Kanji xuất hiện trong nhiều từ vựng hơn lên trước.
            + "(select count(*) from vocabulary_kanji vk where vk.kanji_id = k.id) desc, k.id asc",
            nativeQuery = true)
    List<Kanji> findByHanVietReading(@Param("reading") String likeEscapedReading, Pageable pageable);
}
