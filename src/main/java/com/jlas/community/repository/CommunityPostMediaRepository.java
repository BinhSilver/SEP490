package com.jlas.community.repository;

import com.jlas.community.entity.CommunityPostMedia;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CommunityPostMediaRepository extends JpaRepository<CommunityPostMedia, Long> {

    /** Media của nhiều bài trong một truy vấn (cả trang feed), theo bài rồi theo thứ tự hiển thị. */
    List<CommunityPostMedia> findByPostIdInOrderByPostIdAscSortOrderAscIdAsc(Collection<Long> postIds);
}
