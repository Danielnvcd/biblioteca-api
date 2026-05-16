package com.biblioteca.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.persistence.*;

@Entity
@Table(name = "kpis")
public class KPI {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 100)
    private String nombre;

    @JsonAlias({"valor", "valor_actual"})
    @Column(name = "valor_actual", nullable = false, length = 50)
    private String valorActual;

    @Column(nullable = false, length = 50)
    private String meta;

    @Column(nullable = false, length = 20)
    private String unidad;

    @Column(length = 20)
    private String estado = "ok";

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
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
