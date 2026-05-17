package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public class CreateActivityByFolioRequest {

    @NotBlank
    @Size(max = 50)
    private String folio;

    @NotBlank
    @Size(max = 4000)
    private String descripcion;

    @NotBlank
    @Size(max = 100)
    private String responsable;

    private LocalDate fechaCompromiso;

    @Size(max = 4000)
    private String observaciones;

    public String getFolio() { return folio; }
    public void setFolio(String folio) { this.folio = folio; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public String getResponsable() { return responsable; }
    public void setResponsable(String responsable) { this.responsable = responsable; }
    public LocalDate getFechaCompromiso() { return fechaCompromiso; }
    public void setFechaCompromiso(LocalDate fechaCompromiso) { this.fechaCompromiso = fechaCompromiso; }
    public String getObservaciones() { return observaciones; }
    public void setObservaciones(String observaciones) { this.observaciones = observaciones; }
}
