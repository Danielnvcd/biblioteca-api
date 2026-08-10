package com.biblioteca.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renderizado de los correos.
 *
 * Estos tests existen por una razón concreta: la envoltura HTML se arma con
 * {@code String.formatted}, y un desbalance entre marcadores y argumentos NO
 * se detecta al compilar — revienta al renderizar. Como la plantilla del
 * código se arma ANTES de enviarlo, un fallo ahí no sería "un correo feo":
 * sería una excepción en el camino del inicio de sesión y nadie podría entrar
 * con 2FA por correo.
 *
 * Lo otro que se fija acá es el escapado: el nombre completo y el User-Agent
 * los controla (o los influye) quien inicia sesión, y terminan dentro de un
 * documento que un cliente de correo va a renderizar.
 */
class EmailTemplatesTest {

    private final EmailTemplates templates = new EmailTemplates("Biblioteca Maxipet", "https://app.maxipet.com");

    @Test
    void todasLasPlantillasRenderizanSinReventar() {
        // Si alguna tiene el format string desbalanceado, esto lanza.
        assertThat(templates.loginCode("Ana", "12345678", 10).html()).isNotBlank();
        assertThat(templates.verifyEmailCode("Ana", "12345678", 10).html()).isNotBlank();
        assertThat(templates.loginAlert("Ana", "Chrome · Windows", "1.1.1.1", LocalDateTime.now(), true).html()).isNotBlank();
        assertThat(templates.emailChanged("Ana", "an••••@maxipet.com").html()).isNotBlank();
        assertThat(templates.emailRemoved("Ana").html()).isNotBlank();
        assertThat(templates.alertsDisabled("Ana", LocalDateTime.now()).html()).isNotBlank();
    }

    @Test
    void elCodigoApareceEnElHtmlYEnElTextoPlano() {
        var mail = templates.loginCode("Ana", "13572468", 10);

        // Sin la versión de texto, un cliente en modo texto muestra el marcado
        // crudo y el código queda enterrado entre etiquetas.
        assertThat(mail.html()).contains("13572468");
        assertThat(mail.text()).contains("13572468");
        assertThat(mail.subject()).contains("13572468");
    }

    @Test
    void elNombreDelUsuarioSeEscapa() {
        var mail = templates.loginCode("<script>alert(1)</script>", "12345678", 10);

        // React no interviene acá: el escapado es lo único que separa un nombre
        // hostil de un documento HTML ejecutable en el cliente de correo.
        assertThat(mail.html()).doesNotContain("<script>");
        assertThat(mail.html()).contains("&lt;script&gt;");
    }

    @Test
    void elUserAgentYLaIpDelAvisoSeEscapan() {
        var mail = templates.loginAlert("Ana", "<img src=x onerror=alert(1)>", "\"><b>", LocalDateTime.now(), true);

        assertThat(mail.html()).doesNotContain("<img src=x");
        assertThat(mail.html()).doesNotContain("\"><b>");
    }

    @Test
    void sinUrlPublicaElCorreoSaleSinBotonPeroSigueSiendoValido() {
        EmailTemplates sinUrl = new EmailTemplates("Biblioteca Maxipet", "");

        var mail = sinUrl.loginAlert("Ana", "Chrome", "1.1.1.1", LocalDateTime.now(), false);

        assertThat(mail.html()).isNotBlank();
        assertThat(mail.html()).doesNotContain("<a href");
        // Sin dónde alojarlo no se referencia un logo que no existe: la marca
        // cae al nombre escrito en vez de a una imagen rota.
        assertThat(mail.html()).doesNotContain("<img");
        assertThat(mail.html()).contains("Biblioteca Maxipet");
    }

    @Test
    void elLogoSaleDelFrontendYSobreviveAUnaBarraDeMas() {
        EmailTemplates conBarra = new EmailTemplates("Biblioteca Maxipet", "https://app.maxipet.com/");

        var mail = conBarra.loginCode("Ana", "12345678", 10);

        assertThat(mail.html()).contains("src=\"https://app.maxipet.com/logo.png\"");
        // width como ATRIBUTO, no solo en el CSS: Outlook ignora el ancho por
        // CSS en imágenes y dibujaría los 736 px reales del archivo.
        assertThat(mail.html()).contains("width=\"140\"");
        // El alt lleva el nombre para cuando el cliente bloquea las imágenes.
        assertThat(mail.html()).contains("alt=\"Biblioteca Maxipet\"");
    }

    @Test
    void elAvisoDistingueDispositivoNuevoDeUnoConocido() {
        var nuevo = templates.loginAlert("Ana", "Chrome", "1.1.1.1", LocalDateTime.now(), true);
        var conocido = templates.loginAlert("Ana", "Chrome", "1.1.1.1", LocalDateTime.now(), false);

        assertThat(nuevo.subject()).isNotEqualTo(conocido.subject());
        assertThat(nuevo.subject()).containsIgnoringCase("dispositivo nuevo");
    }
}
