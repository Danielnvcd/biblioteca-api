package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public class SeguridadDto {
    private Integer id;

    @NotBlank
    @Size(max = 100)
    private String titulo;

    @NotBlank
    @Size(max = 4000)
    private String descripcion;

    @Size(max = 20)
    private String nivel;

    private LocalDate fecha;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public String getNivel() { return nivel; }
    public void setNivel(String nivel) { this.nivel = nivel; }
    public LocalDate getFecha() { return fecha; }
    public void setFecha(LocalDate fecha) { this.fecha = fecha; }
}
