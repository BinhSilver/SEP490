package com.jlas.shadowing.repository;

import com.jlas.shadowing.model.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByNameAndTagType(String name, Tag.TagType tagType);
}
