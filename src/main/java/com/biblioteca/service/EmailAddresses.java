package com.biblioteca.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalización, validación y enmascarado de direcciones de correo.
 *
 * Vive aparte porque las tres cosas se necesitan en lugares distintos (alta de
 * correo, envío, y serialización del perfil) y conviene que las tres usen
 * exactamente el mismo criterio: si el validador y el normalizador no
 * coinciden, se puede guardar una dirección que después no pasa la validación.
 */
public final class EmailAddresses {

    private EmailAddresses() {}

    /**
     * Validación deliberadamente conservadora. No intenta cubrir el RFC 5322
     * completo (que admite comillas, comentarios y direcciones que ningún
     * proveedor real acepta): admite lo que la gente escribe y rechaza todo lo
     * demás. Un falso negativo acá es un usuario que reescribe su correo; un
     * falso positivo es una dirección que no recibe nada y deja la cuenta con
     * un segundo factor muerto.
     */
    private static final Pattern VALID = Pattern.compile(
            "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]{1,64}@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
          + "(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");

    public static final int MAX_LENGTH = 254;

    /** Recorta y pasa a minúsculas. Devuelve null si la entrada era vacía. */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * true si la dirección es utilizable. Rechaza explícitamente CR y LF: la
     * API de Resend recibe JSON, así que una inyección de cabeceras no aplica
     * hoy, pero el día que alguien cambie el transporte a SMTP el mismo dato
     * pasaría a construir cabeceras — y ese es exactamente el cambio en el que
     * nadie se acuerda de revisar la validación.
     */
    public static boolean isValid(String normalized) {
        if (normalized == null) return false;
        if (normalized.length() > MAX_LENGTH) return false;
        if (normalized.indexOf('\r') >= 0 || normalized.indexOf('\n') >= 0) return false;
        return VALID.matcher(normalized).matches();
    }

    /**
     * Versión mostrable sin revelar la dirección: {@code da••••@maxipet.com}.
     *
     * Se usa en la pantalla de verificación, donde hay que decirle al usuario a
     * qué buzón mirar sin escribir el correo completo en una pantalla a la que
     * se llega sabiendo solo la contraseña.
     */
    public static String mask(String email) {
        if (email == null || email.isBlank()) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "•••";
        String local = email.substring(0, at);
        String domain = email.substring(at);
        int keep = local.length() <= 2 ? 1 : 2;
        return local.substring(0, keep) + "••••" + domain;
    }
}
