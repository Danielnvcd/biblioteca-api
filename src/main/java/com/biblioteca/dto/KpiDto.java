package com.biblioteca.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create/edit payload for KPI. Excludes {@code id} to prevent mass assignment
 * (sending {"id": N, ...} would overwrite arbitrary rows when bound to the
 * entity directly).
 */
public class KpiDto {
    @NotBlank
    @Size(max = 100)
    private String nombre;

    @JsonAlias({"valor", "valor_actual"})
    @NotBlank
    @Size(max = 50)
    private String valorActual;

    @NotBlank
    @Size(max = 50)
    private String meta;

    @NotBlank
    @Size(max = 20)
    private String unidad;

    @Size(max = 20)
    private String estado;

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }
    public String getValorActual() { return valorActual; }
    public void setValorActual(String valorActual) { this.valorActual = valorActual; }
    public String getMeta() { return meta; }
    public void setMeta(String meta) { this.meta = meta; }
    public String getUnidad() { return unidad; }
    public void setUnidad(String unidad) { this.unidad = unidad; }
    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
