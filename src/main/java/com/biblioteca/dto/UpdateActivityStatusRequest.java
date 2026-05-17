package com.biblioteca.dto;

import jakarta.validation.constraints.Size;

public class UpdateActivityStatusRequest {

    @Size(max = 50)
    private String estatus;

    @Size(max = 4000)
    private String observaciones;

    public String getEstatus() { return estatus; }
    public void setEstatus(String estatus) { this.estatus = estatus; }
    public String getObservaciones() { return observaciones; }
    public void setObservaciones(String observaciones) { this.observaciones = observaciones; }
}
