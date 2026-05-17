package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public class CreateCuestionarioRequest {

    @NotBlank
    @Size(max = 200)
    private String title;

    @Size(max = 4000)
    private String description;

    @NotEmpty(message = "El cuestionario debe tener al menos una pregunta")
    @Size(max = 50)
    private List<@NotBlank @Size(max = 500) String> questions;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getQuestions() { return questions; }
    public void setQuestions(List<String> questions) { this.questions = questions; }
}
