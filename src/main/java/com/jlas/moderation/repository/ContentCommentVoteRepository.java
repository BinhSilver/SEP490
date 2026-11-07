package com.jlas.moderation.repository;

import com.jlas.moderation.entity.ContentCommentVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ContentCommentVoteRepository extends JpaRepository<ContentCommentVote, ContentCommentVote.Id> {
    @Query(value = "select comment_id as commentId, "
            + "sum(case when vote = 1 then 1 else 0 end) as likes, "
            + "sum(case when vote = -1 then 1 else 0 end) as dislikes, "
            + "coalesce(max(case when user_id = :userId then vote end), 0) as myVote "
            + "from content_comment_vote where comment_id in (:ids) group by comment_id", nativeQuery = true)
    List<VoteCounts> countsForComments(@Param("ids") List<Long> ids, @Param("userId") Long userId);

    interface VoteCounts {
        Long getCommentId();
        Long getLikes();
        Long getDislikes();
        Integer getMyVote();
    }
}
