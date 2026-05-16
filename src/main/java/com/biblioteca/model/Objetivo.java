package com.biblioteca.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.persistence.*;

@Entity
@Table(name = "objetivos")
public class Objetivo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 200)
    private String titulo;

    @JsonAlias("proceso_lider")
    @Column(name = "proceso_lider", nullable = false, length = 100)
    private String procesoLider;

    @JsonAlias("procesos_involucrados")
    @Column(name = "procesos_involucrados", length = 200)
    private String procesosInvolucrados;

    @JsonAlias("meta_desc")
    @Column(name = "meta_desc", nullable = false, length = 100)
    private String metaDesc;

    @JsonAlias("resultado_actual")
    @Column(name = "resultado_actual", nullable = false, length = 50)
    private String resultadoActual;

    @Column(length = 20)
    private String estado = "en_meta";

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
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
