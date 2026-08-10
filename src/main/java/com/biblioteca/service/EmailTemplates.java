package com.biblioteca.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Cuerpos de los correos que manda el sistema.
 *
 * TODO valor que venga del usuario o de la request pasa por
 * {@link HtmlUtils#htmlEscape} antes de entrar al HTML. El nombre completo, el
 * User-Agent y la IP los controla (o los influye) quien inicia sesión, y aquí
 * terminan dentro de un documento que un cliente de correo va a renderizar —
 * el mismo razonamiento del SAFE_TEXT_REGEX del perfil, pero aquí sin React
 * escapando de fondo.
 *
 * Cada mensaje lleva versión HTML y versión de texto plano: sin la segunda, un
 * cliente en modo texto muestra el marcado crudo y el código de acceso queda
 * enterrado en etiquetas.
 */
@Component
public class EmailTemplates {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-MX"));

    private static final String BRAND = "#001a6f";
    private static final String INK = "#171b24";
    private static final String MUTED = "#6b7488";
    private static final String BORDER = "#e1e5ec";

    private final String appName;
    private final String appUrl;

    public EmailTemplates(@Value("${app.mail.app-name:Biblioteca Maxipet}") String appName,
                          @Value("${app.mail.app-url:}") String appUrl) {
        this.appName = appName;
        this.appUrl = appUrl;
    }

    // ------------------------------------------------------------------
    // Códigos
    // ------------------------------------------------------------------

    public MailService.Mail loginCode(String displayName, String code, long minutes) {
        String html = wrap("Tu código para iniciar sesión",
                paragraph("Hola " + esc(displayName) + ", usa este código para completar tu inicio de sesión:")
              + codeBlock(code)
              + paragraph("El código vence en " + minutes + " minutos y sirve una sola vez.")
              + warning("Si no estabas iniciando sesión, alguien conoce tu contraseña. "
                      + "Cámbiala cuanto antes desde tu perfil."));
        String text = "Hola " + displayName + ",\n\n"
                + "Tu código para iniciar sesión es: " + code + "\n\n"
                + "Vence en " + minutes + " minutos y sirve una sola vez.\n\n"
                + "Si no estabas iniciando sesión, alguien conoce tu contraseña: cámbiala cuanto antes.\n\n"
                + "— " + appName;
        return new MailService.Mail(appName + " · Código de acceso: " + code, html, text);
    }

    public MailService.Mail verifyEmailCode(String displayName, String code, long minutes) {
        String html = wrap("Confirma tu correo",
                paragraph("Hola " + esc(displayName) + ", ingresa este código en tu perfil para "
                        + "confirmar que esta dirección es tuya:")
              + codeBlock(code)
              + paragraph("El código vence en " + minutes + " minutos.")
              + warning("Si no pediste esto, ignora el mensaje: sin el código la dirección "
                      + "no queda asociada a ninguna cuenta."));
        String text = "Hola " + displayName + ",\n\n"
                + "Tu código de confirmación es: " + code + "\n\n"
                + "Vence en " + minutes + " minutos.\n\n"
                + "Si no pediste esto, ignora el mensaje.\n\n"
                + "— " + appName;
        return new MailService.Mail(appName + " · Confirma tu correo: " + code, html, text);
    }

    // ------------------------------------------------------------------
    // Avisos
    // ------------------------------------------------------------------

    public MailService.Mail loginAlert(String displayName, String device, String ip,
                                       LocalDateTime when, boolean newDevice) {
        String title = newDevice
                ? "Inicio de sesión desde un dispositivo nuevo"
                : "Nuevo inicio de sesión en tu cuenta";
        String lead = newDevice
                ? "Se inició sesión en tu cuenta desde un dispositivo que no habíamos visto antes."
                : "Se inició sesión en tu cuenta.";

        String html = wrap(title,
                paragraph("Hola " + esc(displayName) + ", " + lead)
              + detailTable(device, ip, when)
              + paragraph("Si fuiste tú, no tienes que hacer nada.")
              + warning("Si no reconoces este acceso, cambia tu contraseña y cierra las demás "
                      + "sesiones desde tu perfil.")
              + button("Ir a mi perfil", appUrl.isBlank() ? null : appUrl + "/perfil"));
        String text = "Hola " + displayName + ",\n\n" + lead + "\n\n"
                + "Dispositivo: " + device + "\n"
                + "IP: " + ip + "\n"
                + "Fecha: " + WHEN.format(when) + "\n\n"
                + "Si no reconoces este acceso, cambia tu contraseña y cierra las demás sesiones "
                + "desde tu perfil.\n\n"
                + "— " + appName;
        return new MailService.Mail(appName + " · " + title, html, text);
    }

    /**
     * Aviso al correo ANTERIOR cuando la cuenta cambia de dirección. Es la
     * señal que delata un secuestro: quien entra a una cuenta ajena lo primero
     * que hace es mover el correo de recuperación, y si el aviso solo fuera a
     * la dirección nueva el dueño real no se enteraría nunca.
     */
    public MailService.Mail emailChanged(String displayName, String maskedNew) {
        String html = wrap("El correo de tu cuenta cambió",
                paragraph("Hola " + esc(displayName) + ", el correo asociado a tu cuenta se cambió "
                        + "a <strong>" + esc(maskedNew) + "</strong>.")
              + paragraph("Este mensaje va a tu dirección anterior. A partir de ahora, los códigos "
                        + "y avisos van a la nueva.")
              + warning("Si no hiciste este cambio, tu cuenta está comprometida: avisa a un "
                      + "administrador de inmediato."));
        String text = "Hola " + displayName + ",\n\n"
                + "El correo asociado a tu cuenta se cambió a " + maskedNew + ".\n\n"
                + "Si no hiciste este cambio, tu cuenta está comprometida: avisa a un "
                + "administrador de inmediato.\n\n"
                + "— " + appName;
        return new MailService.Mail(appName + " · El correo de tu cuenta cambió", html, text);
    }

    /**
     * Aviso de que se apagaron los avisos. Suena redundante y no lo es: apagar
     * la notificación es lo primero que haría quien entró a una cuenta ajena,
     * y este mensaje es la última oportunidad del dueño de enterarse.
     */
    public MailService.Mail alertsDisabled(String displayName, LocalDateTime when) {
        String html = wrap("Se desactivaron los avisos de inicio de sesión",
                paragraph("Hola " + esc(displayName) + ", los avisos de inicio de sesión de tu "
                        + "cuenta quedaron desactivados el " + esc(WHEN.format(when)) + ".")
              + paragraph("Este es el último aviso que vas a recibir sobre accesos a tu cuenta.")
              + warning("Si no fuiste tú, alguien con tu contraseña está intentando entrar sin "
                      + "que te enteres. Cámbiala ahora y avisa a un administrador.")
              + button("Ir a mi perfil", appUrl.isBlank() ? null : appUrl + "/perfil"));
        String text = "Hola " + displayName + ",\n\n"
                + "Los avisos de inicio de sesión de tu cuenta quedaron desactivados el "
                + WHEN.format(when) + ". Este es el último aviso que vas a recibir sobre accesos.\n\n"
                + "Si no fuiste tú, cambia tu contraseña ahora y avisa a un administrador.\n\n"
                + "— " + appName;
        return new MailService.Mail(appName + " · Se desactivaron los avisos de inicio de sesión", html, text);
    }

    public MailService.Mail emailRemoved(String displayName) {
        String html = wrap("Se quitó el correo de tu cuenta",
                paragraph("Hola " + esc(displayName) + ", esta dirección ya no está asociada a tu "
                        + "cuenta. Dejás de recibir códigos de acceso y avisos de inicio de sesión.")
              + warning("Si no hiciste este cambio, tu cuenta está comprometida: avisa a un "
                      + "administrador de inmediato."));
        String text = "Hola " + displayName + ",\n\n"
                + "Esta dirección ya no está asociada a tu cuenta.\n\n"
                + "Si no hiciste este cambio, avisa a un administrador de inmediato.\n\n"
                + "— " + appName;
        return new MailService.Mail(appName + " · Se quitó el correo de tu cuenta", html, text);
    }

    // ------------------------------------------------------------------
    // Armado del HTML
    // ------------------------------------------------------------------

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s == null ? "" : s);
    }

    /**
     * Envoltura común. Tabla y estilos EN LÍNEA porque los clientes de correo
     * ignoran las hojas de estilo externas y buena parte de flexbox/grid.
     */
    private String wrap(String title, String body) {
        return """
            <div style="margin:0;padding:24px 12px;background:#f7f8fa;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
              <table role="presentation" cellpadding="0" cellspacing="0" border="0" width="100%%" style="max-width:520px;margin:0 auto;background:#ffffff;border:1px solid %s;border-radius:12px;">
                <tr>
                  <td style="padding:28px 32px 8px 32px;">
                    %s
                    <h1 style="margin:16px 0 0 0;font-size:20px;line-height:1.3;font-weight:600;color:%s;">%s</h1>
                  </td>
                </tr>
                <tr><td style="padding:8px 32px 28px 32px;">%s</td></tr>
                <tr>
                  <td style="padding:16px 32px 24px 32px;border-top:1px solid %s;">
                    <p style="margin:0;font-size:12px;line-height:1.6;color:%s;">
                      Este es un mensaje automático de %s. No respondas a esta dirección.
                    </p>
                  </td>
                </tr>
              </table>
            </div>
            """.formatted(BORDER, header(), INK, esc(title), body, BORDER, MUTED, esc(appName));
    }

    /**
     * Cabecera de marca: el logo si hay dónde alojarlo, y si no el nombre
     * escrito.
     *
     * Por qué una URL remota y no la imagen incrustada: en correo hay tres
     * caminos y dos no sirven. Los {@code data:} URI los descarta Gmail sin
     * avisar, y los adjuntos en línea ({@code cid:}) obligan a mandar el binario
     * en cada mensaje y dependen de que el proveedor los soporte. Una URL
     * pública es lo que hacen todos y lo que mejor se comporta.
     *
     * El logo sale del FRONTEND ({@code app-url} + /logo.png), que ya lo publica
     * para la pantalla de login. Se aprovecha que ya está ahí en vez de montar
     * otro sitio donde alojarlo — y como la API exige sesión en /api/files, ese
     * camino no servía.
     *
     * Detalles que parecen menores y no lo son:
     *
     *  - Muchos clientes (Outlook de escritorio, buena parte del correo
     *    corporativo) BLOQUEAN las imágenes remotas por defecto. Por eso el
     *    {@code alt} lleva estilos propios: cuando la imagen no carga, el texto
     *    alternativo aparece con el color y el peso de la marca en vez de un
     *    ícono roto.
     *
     *  - El {@code width} va como atributo HTML además de en el CSS: Outlook
     *    ignora el ancho por CSS en las imágenes y las dibuja a tamaño real (el
     *    archivo mide 736 px, o sea que reventaría el ancho de la tarjeta).
     *
     *  - Se muestra a 140 px teniendo 736 nativos, así que en pantallas de alta
     *    densidad se ve nítido sin subir una versión aparte.
     */
    private String header() {
        if (appUrl.isBlank()) {
            return "<div style=\"font-size:18px;font-weight:700;letter-spacing:-.01em;color:"
                 + BRAND + ";\">" + esc(appName) + "</div>";
        }
        String src = appUrl.replaceAll("/+$", "") + "/logo.png";
        return "<img src=\"" + esc(src) + "\" alt=\"" + esc(appName) + "\" width=\"140\""
             + " style=\"display:block;border:0;outline:none;text-decoration:none;"
             + "width:140px;max-width:140px;height:auto;"
             // Estilos que solo se ven si el cliente bloquea la imagen: el alt
             // hereda tipografía y color, y así el bloqueo degrada a la marca
             // escrita en vez de a un recuadro vacío.
             + "font-size:18px;font-weight:700;color:" + BRAND + ";\">";
    }

    private static String paragraph(String htmlContent) {
        return "<p style=\"margin:16px 0 0 0;font-size:14px;line-height:1.65;color:" + INK + ";\">"
                + htmlContent + "</p>";
    }

    /**
     * El código, grande y espaciado. Va con letter-spacing y monoespaciada
     * para que no se confundan 0/O ni 1/l al copiarlo a mano desde el teléfono.
     */
    private static String codeBlock(String code) {
        return "<div style=\"margin:24px 0;padding:18px;background:#f7f8fa;border:1px solid " + BORDER
             + ";border-radius:10px;text-align:center;\">"
             + "<span style=\"font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;"
             + "font-size:30px;font-weight:700;letter-spacing:.22em;color:" + BRAND + ";\">"
             + esc(code) + "</span></div>";
    }

    private static String detailTable(String device, String ip, LocalDateTime when) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" width=\"100%\" "
             + "style=\"margin:20px 0;border:1px solid " + BORDER + ";border-radius:10px;\">"
             + row("Dispositivo", device)
             + row("Dirección IP", ip)
             + row("Fecha", WHEN.format(when))
             + "</table>";
    }

    private static String row(String label, String value) {
        return "<tr>"
             + "<td style=\"padding:10px 16px;font-size:13px;color:" + MUTED + ";width:40%;\">" + esc(label) + "</td>"
             + "<td style=\"padding:10px 16px;font-size:13px;color:" + INK + ";font-weight:500;\">"
             + esc(value == null || value.isBlank() ? "—" : value) + "</td>"
             + "</tr>";
    }

    private static String warning(String text) {
        return "<div style=\"margin:20px 0 0 0;padding:14px 16px;background:#fffbeb;border:1px solid #fde68a;"
             + "border-radius:10px;font-size:13px;line-height:1.6;color:#78350f;\">" + text + "</div>";
    }

    /** Botón que se omite entero si no hay URL pública configurada. */
    private static String button(String label, String url) {
        if (url == null || url.isBlank()) return "";
        return "<div style=\"margin:24px 0 0 0;\"><a href=\"" + esc(url) + "\" "
             + "style=\"display:inline-block;padding:11px 20px;background:" + BRAND + ";color:#ffffff;"
             + "font-size:14px;font-weight:600;text-decoration:none;border-radius:8px;\">"
             + esc(label) + "</a></div>";
    }
}
