package com.biblioteca.repository;

import com.biblioteca.model.Questionnaire;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface QuestionnaireRepository extends JpaRepository<Questionnaire, Integer> {
    List<Questionnaire> findAllByOrderByCreatedAtDesc();
}
