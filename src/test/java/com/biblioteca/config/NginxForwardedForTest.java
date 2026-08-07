package com.biblioteca.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardarraíl sobre los configs de nginx versionados en el repo.
 *
 * `$proxy_add_x_forwarded_for` se expande a "$http_x_forwarded_for,
 * $remote_addr": CONSERVA el X-Forwarded-For que mandó el cliente y le anexa
 * la IP real al final. Spring, con forward-headers-strategy=framework, resuelve
 * la dirección remota tomando el PRIMER token de esa lista — el que eligió el
 * cliente. Con eso, cualquiera podía fijar la IP que ve la aplicación y:
 *
 *   - evadir el rate-limit por IP de RateLimitFilter rotando el valor,
 *   - ensuciar la bitácora (AuditService guarda getRemoteAddr()),
 *   - falsear la IP que /api/auth/sessions le muestra al usuario para que
 *     detecte accesos que no reconoce.
 *
 * Es la variable que aparece en casi todos los ejemplos de nginx, así que
 * vuelve a colarse con facilidad en un copy-paste. Este test es barato y la
 * detecta en el acto; no valida el nginx que corre en el VPS, solo que lo
 * versionado en el repo no reintroduzca el patrón.
 */
class NginxForwardedForTest {

    private static final List<String> CONFIGS = List.of(
            "nginx/sites-available/biblioteca-api",
            "nginx.config");

    /**
     * Directivas efectivas: se descartan las líneas de comentario, porque los
     * propios configs explican en prosa por qué NO se usa la variable insegura
     * y esa mención no debe contar como uso.
     */
    private static String directivas(String rel) throws IOException {
        Path path = Paths.get(rel);
        assertThat(path)
                .as("el config %s dejó de existir — actualizá esta lista", rel)
                .exists();
        return Files.readAllLines(path).stream()
                .filter(line -> !line.strip().startsWith("#"))
                .reduce("", (a, b) -> a + b + "\n");
    }

    @Test
    void ningunConfigAnexaElXForwardedForDelCliente() throws IOException {
        for (String rel : CONFIGS) {
            assertThat(directivas(rel))
                    .as("%s usa $proxy_add_x_forwarded_for: el cliente puede fijar "
                      + "la IP que ve Spring. Usar $remote_addr, que REEMPLAZA el header.", rel)
                    .doesNotContain("$proxy_add_x_forwarded_for");
        }
    }

    @Test
    void todoConfigQueProxieaFijaElXForwardedForALaIpReal() throws IOException {
        for (String rel : CONFIGS) {
            String contenido = directivas(rel);
            if (!contenido.contains("proxy_pass")) {
                continue;
            }
            assertThat(contenido)
                    .as("%s hace proxy_pass pero no fija X-Forwarded-For a $remote_addr; "
                      + "sin eso Spring confía en el header que mande el cliente", rel)
                    .containsPattern("proxy_set_header\\s+X-Forwarded-For\\s+\\$remote_addr\\s*;");
        }
    }
}
