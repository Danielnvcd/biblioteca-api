package com.biblioteca.repository;

import com.biblioteca.model.QuestionnaireResponse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionnaireResponseRepository extends JpaRepository<QuestionnaireResponse, Integer> {
    List<QuestionnaireResponse> findByQuestionnaireIdOrderByCreatedAtDesc(Integer questionnaireId);
    long countByQuestionnaireId(Integer questionnaireId);
    boolean existsByQuestionnaireIdAndUserName(Integer questionnaireId, String userName);
}
