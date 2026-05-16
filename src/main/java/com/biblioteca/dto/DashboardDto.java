package com.biblioteca.dto;

import java.util.List;
import java.util.Map;

public class DashboardDto {
    private Map<String, Long> quejasStats;
    private List<SeguridadDto> alertas;
    private long diasSinAccidentes;
    private List<ContentDto> ultimosDocumentos;

    public Map<String, Long> getQuejasStats() { return quejasStats; }
    public void setQuejasStats(Map<String, Long> quejasStats) { this.quejasStats = quejasStats; }
    public List<SeguridadDto> getAlertas() { return alertas; }
    public void setAlertas(List<SeguridadDto> alertas) { this.alertas = alertas; }
    public long getDiasSinAccidentes() { return diasSinAccidentes; }
    public void setDiasSinAccidentes(long diasSinAccidentes) { this.diasSinAccidentes = diasSinAccidentes; }
    public List<ContentDto> getUltimosDocumentos() { return ultimosDocumentos; }
    public void setUltimosDocumentos(List<ContentDto> ultimosDocumentos) { this.ultimosDocumentos = ultimosDocumentos; }
}
