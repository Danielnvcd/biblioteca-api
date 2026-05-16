package com.biblioteca.dto;

import java.time.LocalDate;
import java.util.List;

public class CorrectiveActionDto {
    private Integer id;
    private String folio;
    private LocalDate fechaReporte;
    private String origen;
    private String reporta;
    private String descripcion;
    private String medidaTomada;
    private String departamento;
    private LocalDate fechaEval1;
    private LocalDate fechaEval2;
    private String estatus;
    private List<CorrectionActivityDto> activities;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getFolio() { return folio; }
    public void setFolio(String folio) { this.folio = folio; }
    public LocalDate getFechaReporte() { return fechaReporte; }
    public void setFechaReporte(LocalDate fechaReporte) { this.fechaReporte = fechaReporte; }
    public String getOrigen() { return origen; }
    public void setOrigen(String origen) { this.origen = origen; }
    public String getReporta() { return reporta; }
    public void setReporta(String reporta) { this.reporta = reporta; }
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }
    public String getMedidaTomada() { return medidaTomada; }
    public void setMedidaTomada(String medidaTomada) { this.medidaTomada = medidaTomada; }
    public String getDepartamento() { return departamento; }
    public void setDepartamento(String departamento) { this.departamento = departamento; }
    public LocalDate getFechaEval1() { return fechaEval1; }
    public void setFechaEval1(LocalDate fechaEval1) { this.fechaEval1 = fechaEval1; }
    public LocalDate getFechaEval2() { return fechaEval2; }
    public void setFechaEval2(LocalDate fechaEval2) { this.fechaEval2 = fechaEval2; }
    public String getEstatus() { return estatus; }
    public void setEstatus(String estatus) { this.estatus = estatus; }
    public List<CorrectionActivityDto> getActivities() { return activities; }
    public void setActivities(List<CorrectionActivityDto> activities) { this.activities = activities; }
}
