package com.biblioteca.service;

import com.biblioteca.model.KnownDevice;
import com.biblioteca.model.User;
import com.biblioteca.repository.KnownDeviceRepository;
import com.biblioteca.security.DeviceCookieFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Avisos por correo de inicio de sesión, y el registro de dispositivos
 * conocidos que los hace útiles.
 *
 * El objetivo del aviso es que el usuario note el acceso que NO hizo. Eso
 * impone dos restricciones que mandan sobre el diseño:
 *
 *  - Un aviso que salta todos los días se ignora. Por eso el modo por defecto
 *    es "solo dispositivos nuevos", y por eso el dispositivo se identifica con
 *    una cookie estable y no con la IP: la IP cambia sola al pasar de wifi a
 *    datos móviles, y avisar por eso enseña al usuario a archivar el mensaje
 *    sin leerlo, que es exactamente cómo un aviso real pasa desapercibido.
 *
 *  - Un aviso que no llega no sirve, pero un aviso que ROMPE el login sirve
 *    todavía menos. Todo lo de aquí está envuelto para que un fallo (correo
 *    caído, base con problemas) se registre y siga de largo: el usuario ya
 *    demostró quién es y tiene que poder entrar.
 */
@Service
public class LoginAlertService {

    private static final Logger log = LoggerFactory.getLogger(LoginAlertService.class);

    public static final String ALERTS_OFF = "off";
    public static final String ALERTS_NEW_DEVICE = "new_device";
    public static final String ALERTS_ALWAYS = "always";

    /**
     * Resultado de mirar la cookie de dispositivo.
     *
     * @param cookieValue valor a devolver en la respuesta. Siempre viene con
     *                    contenido: se reemite en cada inicio de sesión para
     *                    renovar el vencimiento de la cookie, de modo que un
     *                    usuario activo no vuelva a "dispositivo nuevo" solo
     *                    porque pasaron 400 días.
     * @param isNew       true si esta cuenta nunca había iniciado sesión desde
     *                    este navegador.
     */
    public record DeviceCheck(String cookieValue, boolean isNew, String label) {}

    private final KnownDeviceRepository deviceRepository;
    private final DeviceCookieFactory cookieFactory;
    private final MailService mailService;
    private final EmailTemplates templates;

    public LoginAlertService(KnownDeviceRepository deviceRepository,
                             DeviceCookieFactory cookieFactory,
                             MailService mailService,
                             EmailTemplates templates) {
        this.deviceRepository = deviceRepository;
        this.cookieFactory = cookieFactory;
        this.mailService = mailService;
        this.templates = templates;
    }

    /**
     * Registra el dispositivo y dice si era nuevo para esta cuenta.
     *
     * Ante cualquier error devuelve una cookie nueva marcada como "no es
     * nuevo": tratar un fallo de base como dispositivo nuevo dispararía una
     * ráfaga de avisos falsos justo cuando el sistema ya está en problemas.
     */
    public DeviceCheck registerDevice(Integer userId, String rawCookie, String userAgent, String ip) {
        String label = deviceLabel(userAgent);
        try {
            String value = (rawCookie == null || rawCookie.isBlank())
                    ? cookieFactory.newValue()
                    : rawCookie;
            String hash = sha256(value);
            LocalDateTime now = LocalDateTime.now();

            Optional<KnownDevice> existing = deviceRepository.findByUserIdAndDeviceHash(userId, hash);
            if (existing.isPresent()) {
                deviceRepository.touch(existing.get().getId(), now, ip);
                return new DeviceCheck(value, false, existing.get().getLabel() != null
                        ? existing.get().getLabel() : label);
            }

            // Primer dispositivo de la cuenta: se registra en silencio. Marcarlo
            // como "nuevo" haría que activar el correo dispare un aviso sobre la
            // sesión que el usuario tiene abierta en ese preciso momento, que es
            // ruido puro.
            boolean firstEver = !deviceRepository.existsByUserId(userId);

            KnownDevice device = new KnownDevice();
            device.setUserId(userId);
            device.setDeviceHash(hash);
            device.setLabel(label);
            device.setLastIp(ip);
            device.setFirstSeen(now);
            device.setLastSeen(now);
            try {
                deviceRepository.save(device);
            } catch (DataIntegrityViolationException race) {
                // Dos inicios de sesión simultáneos desde el mismo navegador
                // chocan contra la unique (user_id, device_hash). El que pierde
                // ya tiene el dispositivo registrado, así que no es nuevo.
                return new DeviceCheck(value, false, label);
            }
            return new DeviceCheck(value, !firstEver, label);
        } catch (Exception e) {
            log.warn("No se pudo registrar el dispositivo de userId={}: {}", userId, e.getMessage());
            return new DeviceCheck(cookieFactory.newValue(), false, label);
        }
    }

    /**
     * Manda el aviso si corresponde según la preferencia del usuario. Nunca
     * lanza: se llama desde el camino del inicio de sesión.
     */
    public void notifyLogin(User user, DeviceCheck device, String ip) {
        try {
            if (!user.hasUsableEmail()) return;
            if (!shouldNotify(user.getLoginAlerts(), device.isNew())) return;

            mailService.sendAsync(user.getEmail(), templates.loginAlert(
                    displayName(user), device.label(), ip, LocalDateTime.now(), device.isNew()));
        } catch (Exception e) {
            log.warn("No se pudo encolar el aviso de inicio de sesión de userId={}: {}",
                    user.getId(), e.getMessage());
        }
    }

    static boolean shouldNotify(String preference, boolean newDevice) {
        if (ALERTS_ALWAYS.equals(preference)) return true;
        if (ALERTS_NEW_DEVICE.equals(preference)) return newDevice;
        return false; // 'off' y cualquier valor inesperado
    }

    /**
     * Etiqueta legible a partir del User-Agent. No pretende ser exacta: alcanza
     * con que alguien reconozca "ese es mi teléfono" de un vistazo.
     *
     * El orden importa — el UA de Chrome también contiene "Safari" y el de Edge
     * contiene los dos, así que se descarta del más específico al más genérico.
     * Es el mismo criterio que usa deviceLabel() en el frontend; si uno cambia,
     * conviene cambiar el otro para que el correo y la pantalla de sesiones no
     * llamen distinto al mismo dispositivo.
     */
    static String deviceLabel(String ua) {
        if (ua == null || ua.isBlank()) return "Dispositivo desconocido";
        String browser =
                ua.contains("Edg/")     ? "Edge" :
                ua.contains("OPR/")     ? "Opera" :
                ua.contains("Chrome/")  ? "Chrome" :
                ua.contains("Firefox/") ? "Firefox" :
                ua.contains("Safari/")  ? "Safari" : "Navegador";
        String os =
                ua.contains("Windows")  ? "Windows" :
                ua.contains("Android")  ? "Android" :
                (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) ? "iOS" :
                ua.contains("Mac OS X") ? "macOS" :
                ua.contains("Linux")    ? "Linux" : "";
        return os.isEmpty() ? browser : browser + " · " + os;
    }

    private static String displayName(User user) {
        String full = user.getFullName();
        return full != null && !full.isBlank() ? full : user.getUsername();
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
