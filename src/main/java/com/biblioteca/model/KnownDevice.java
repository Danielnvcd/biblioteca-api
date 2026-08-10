package com.biblioteca.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Dispositivo desde el que la cuenta ya inició sesión alguna vez. Sirve para
 * que el aviso por correo distinga "entraste desde tu compu de siempre" de
 * "alguien entró desde un lugar nuevo".
 *
 * La identidad la da una cookie opaca y aleatoria (se guarda su SHA-256), no
 * la IP: la IP cambia sola al pasar de wifi a datos móviles, y un aviso que
 * salta sin motivo se vuelve ruido que el usuario aprende a ignorar — que es
 * exactamente cómo un aviso real termina pasando desapercibido.
 */
@Entity
@Table(name = "known_devices",
       uniqueConstraints = @UniqueConstraint(name = "ux_known_devices_user_hash",
                                             columnNames = {"user_id", "device_hash"}),
       indexes = @Index(name = "idx_known_devices_user_id", columnList = "user_id"))
public class KnownDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "device_hash", nullable = false, length = 64)
    private String deviceHash;

    @Column(length = 120)
    private String label;

    @Column(name = "last_ip", length = 45)
    private String lastIp;

    @Column(name = "first_seen", nullable = false)
    private LocalDateTime firstSeen;

    @Column(name = "last_seen", nullable = false)
    private LocalDateTime lastSeen;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Integer getUserId() { return userId; }
    public void setUserId(Integer userId) { this.userId = userId; }
    public String getDeviceHash() { return deviceHash; }
    public void setDeviceHash(String deviceHash) { this.deviceHash = deviceHash; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getLastIp() { return lastIp; }
    public void setLastIp(String lastIp) { this.lastIp = lastIp; }
    public LocalDateTime getFirstSeen() { return firstSeen; }
    public void setFirstSeen(LocalDateTime firstSeen) { this.firstSeen = firstSeen; }
    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }
}
