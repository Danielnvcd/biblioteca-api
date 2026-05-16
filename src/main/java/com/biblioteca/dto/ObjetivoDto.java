package com.biblioteca.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/edit payload for Objetivo — excludes {@code id} to prevent
 * mass assignment overwrites.
 */
public class ObjetivoDto {
    @NotBlank
    @Size(max = 200)
    private String titulo;

    @JsonAlias("proceso_lider")
    @NotBlank
    @Size(max = 100)
    private String procesoLider;

    @JsonAlias("procesos_involucrados")
    @Size(max = 200)
    private String procesosInvolucrados;

    @JsonAlias("meta_desc")
    @NotBlank
    @Size(max = 100)
    private String metaDesc;

    @JsonAlias("resultado_actual")
    @NotBlank
    @Size(max = 50)
    private String resultadoActual;

    @Size(max = 20)
    private String estado;

    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }
    public String getProcesoLider() { return procesoLider; }
    public void setProcesoLider(String procesoLider) { this.procesoLider = procesoLider; }
    public String getProcesosInvolucrados() { return procesosInvolucrados; }
    public void setProcesosInvolucrados(String procesosInvolucrados) { this.procesosInvolucrados = procesosInvolucrados; }
    public String getMetaDesc() { return metaDesc; }
    public void setMetaDesc(String metaDesc) { this.metaDesc = metaDesc; }
    public String getResultadoActual() { return resultadoActual; }
    public void setResultadoActual(String resultadoActual) { this.resultadoActual = resultadoActual; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
