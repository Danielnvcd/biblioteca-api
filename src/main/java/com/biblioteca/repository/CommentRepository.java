package com.biblioteca.repository;

import com.biblioteca.model.Comment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Integer> {
    List<Comment> findByContentIdInOrderByCreatedAtAsc(Collection<String> contentIds);
}
