package com.jlas.kaiwa.repository;

import com.jlas.kaiwa.model.Category;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByActiveTrueOrderByDisplayOrderAsc();
    Optional<Category> findByCode(String code);
}
