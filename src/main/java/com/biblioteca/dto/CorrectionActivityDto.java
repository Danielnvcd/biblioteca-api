package com.biblioteca.dto;

import java.time.LocalDate;

public class CorrectionActivityDto {
    private Integer id;
    private Integer actionId;
    private String descripcion;
    private String responsable;
    private LocalDate fechaCompromiso;
    private String estatus;
    private String observaciones;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getActionId() { return actionId; }
    public void setActionId(Integer actionId) { this.actionId = actionId; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public String getResponsable() { return responsable; }
    public void setResponsable(String responsable) { this.responsable = responsable; }
    public LocalDate getFechaCompromiso() { return fechaCompromiso; }
    public void setFechaCompromiso(LocalDate fechaCompromiso) { this.fechaCompromiso = fechaCompromiso; }
    public String getEstatus() { return estatus; }
    public void setEstatus(String estatus) { this.estatus = estatus; }
    public String getObservaciones() { return observaciones; }
    public void setObservaciones(String observaciones) { this.observaciones = observaciones; }
}
