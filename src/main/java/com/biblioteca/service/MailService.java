package com.biblioteca.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Envío de correo transaccional vía Resend.
 *
 * Se habla la API HTTP directo con {@link RestClient} en vez de sumar el SDK:
 * es un POST con JSON, y una dependencia menos es una dependencia menos que
 * auditar y actualizar.
 *
 * DOS MODOS, y la diferencia importa:
 *
 *  - {@link #send} es sincrónico y devuelve si el mensaje salió. Lo usan los
 *    flujos donde el usuario está esperando el correo (el código de acceso):
 *    ahí hay que poder decirle "no se pudo enviar" en vez de dejarlo mirando
 *    una bandeja que nunca se va a llenar.
 *
 *  - {@link #sendAsync} no bloquea y se traga cualquier error. Lo usan los
 *    avisos (inicio de sesión, cambio de correo), que acompañan a una
 *    operación ya terminada. Un incidente de Resend NO puede convertir un
 *    /login exitoso en un 500: el usuario ya probó quién es, y quedarse sin
 *    aviso es peor que nada pero muchísimo mejor que quedarse sin entrar.
 *
 * Ninguno de los dos loguea nunca la API key ni el cuerpo del mensaje (que
 * lleva códigos de un solo uso en claro).
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    /** Cuerpo listo para enviar. */
    public record Mail(String subject, String html, String text) {}

    private final boolean enabled;
    private final String apiKey;
    private final String from;
    private final RestClient client;

    public MailService(@Value("${app.mail.enabled:false}") boolean enabled,
                       @Value("${app.mail.api-key:}") String apiKey,
                       @Value("${app.mail.from:}") String from,
                       @Value("${app.mail.base-url:https://api.resend.com}") String baseUrl) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.from = from;

        // Timeouts explícitos. Sin ellos, un Resend que acepta la conexión y
        // deja de contestar cuelga el thread de Tomcat hasta el timeout del
        // sistema operativo — y el envío del código está en el camino de un
        // request de usuario. 5 s de lectura es holgado para esta API.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();

        if (!enabled) {
            log.info("Envío de correo DESHABILITADO (app.mail.enabled=false) — "
                   + "los códigos y avisos no se envían.");
        }
    }

    /** true si la configuración permite enviar. Los flujos que dependen del
     *  correo lo consultan para dar un error claro en vez de fallar al enviar. */
    public boolean isEnabled() {
        return enabled && !apiKey.isBlank() && !from.isBlank();
    }

    /**
     * Envía y devuelve true si Resend aceptó el mensaje. No lanza: los errores
     * se loguean y se reportan con el valor de retorno, para que cada caller
     * decida qué hacer sin tener que envolver todo en try/catch.
     */
    public boolean send(String to, Mail mail) {
        if (!isEnabled()) {
            log.debug("Correo no enviado a {} — envío deshabilitado", EmailAddresses.mask(to));
            return false;
        }
        if (!EmailAddresses.isValid(to)) {
            log.warn("Correo no enviado — dirección inválida");
            return false;
        }
        try {
            client.post()
                    .uri("/emails")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "from", from,
                            "to", List.of(to),
                            "subject", mail.subject(),
                            "html", mail.html(),
                            "text", mail.text()))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Correo enviado a {} — {}", EmailAddresses.mask(to), mail.subject());
            return true;
        } catch (Exception e) {
            // Solo el tipo y el mensaje de la excepción. El cuerpo de la
            // respuesta de Resend puede repetir el contenido del mensaje, y
            // este método manda códigos de un solo uso.
            log.error("Fallo al enviar correo a {} ({}): {}",
                    EmailAddresses.mask(to), e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /**
     * Igual que {@link #send} pero fuera del thread del request. Para avisos,
     * donde el resultado no cambia lo que se le responde al usuario.
     */
    @Async("mailExecutor")
    public void sendAsync(String to, Mail mail) {
        send(to, mail);
    }
}
