package com.jlas.dictionary.repository;

import com.jlas.dictionary.entity.Kanji;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

/**
 * Repository cho {@link Kanji}. So khớp ký tự dùng {@code COLLATE utf8mb4_bin} (xem ghi chú ở
 * {@link VocabularyEntryRepository}) để tránh utf8mb4_unicode_ci coi các ký tự Kanji khác nhau
 * là tương đương.
 */
@Repository
public interface KanjiRepository extends JpaRepository<Kanji, Long> {

    @Query(value = "select k.* from kanji k where binary k.`character` = binary :value limit 1",
            nativeQuery = true)
    Optional<Kanji> findByCharacter(@Param("value") String character);

    // IDE static SQL linter không hiểu cú pháp mở rộng list-parameter "IN (:values)" của Spring
    // Data JPA (nó expand thành (?,?,?...) lúc runtime) nên báo sai cú pháp ở đây — đã kiểm tra
    // đây là hạn chế của linter, không phải lỗi thật; đây là cách dùng chuẩn, có tài liệu của
    // Spring Data JPA cho native query với tham số List.
    @Query(value = "select k.* from kanji k where binary k.`character` in (:values)",
            nativeQuery = true)
    List<Kanji> findByCharacterIn(@Param("values") List<String> characters);
}
