package com.biblioteca.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class QuejaDto {
    private Integer id;

    @NotBlank
    @Size(max = 20)
    private String folio;

    private LocalDate fecha;

    @NotBlank
    @Size(max = 100)
    private String cliente;

    @NotBlank
    @Size(max = 200)
    private String motivo;

    @Size(max = 4000)
    private String solucion;

    @Size(max = 20)
    private String estado;

    @Size(max = 255)
    private String solucionImagen;

    private LocalDateTime createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getFolio() { return folio; }
    public void setFolio(String folio) { this.folio = folio; }
    public LocalDate getFecha() { return fecha; }
    public void setFecha(LocalDate fecha) { this.fecha = fecha; }
    public String getCliente() { return cliente; }
    public void setCliente(String cliente) { this.cliente = cliente; }
    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }
    public String getSolucion() { return solucion; }
    public void setSolucion(String solucion) { this.solucion = solucion; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
    public String getSolucionImagen() { return solucionImagen; }
    public void setSolucionImagen(String solucionImagen) { this.solucionImagen = solucionImagen; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
