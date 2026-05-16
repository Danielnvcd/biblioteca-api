package com.biblioteca.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "corrective_action")
public class CorrectiveAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 50)
    private String folio;

    @Column(name = "fecha_reporte")
    private LocalDate fechaReporte;

    @Column(nullable = false, length = 100)
    private String origen;

    @Column(nullable = false, length = 100)
    private String reporta;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String descripcion;

    @Column(name = "medida_tomada", length = 200)
    private String medidaTomada = "Acción correctiva";

    @Column(length = 100)
    private String departamento;

    @Column(name = "fecha_eval_1")
    private LocalDate fechaEval1;

    @Column(name = "fecha_eval_2")
    private LocalDate fechaEval2;

    @Column(length = 50)
    private String estatus = "Pendiente";

    @OneToMany(mappedBy = "action", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CorrectionActivity> activities = new ArrayList<>();

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
    public List<CorrectionActivity> getActivities() { return activities; }
    public void setActivities(List<CorrectionActivity> activities) { this.activities = activities; }
}
