package com.biblioteca.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalización, validación y enmascarado de direcciones.
 *
 * La validación es la puerta de entrada de todo lo demás: una dirección que
 * pasa acá termina recibiendo códigos de acceso, así que un formato raro que
 * se cuele es una cuenta con un segundo factor que no funciona.
 */
class EmailAddressesTest {

    @Test
    void normalizaRecortandoYABajaCaja() {
        assertThat(EmailAddresses.normalize("  Ana.Perez@Maxipet.COM  "))
                .isEqualTo("ana.perez@maxipet.com");
        assertThat(EmailAddresses.normalize("   ")).isNull();
        assertThat(EmailAddresses.normalize(null)).isNull();
    }

    @Test
    void aceptaDireccionesNormales() {
        assertThat(EmailAddresses.isValid("ana@maxipet.com")).isTrue();
        assertThat(EmailAddresses.isValid("ana.perez+etiqueta@sub.maxipet.com.mx")).isTrue();
    }

    @Test
    void rechazaLoQueNoEsUnaDireccion() {
        assertThat(EmailAddresses.isValid("ana")).isFalse();
        assertThat(EmailAddresses.isValid("ana@")).isFalse();
        assertThat(EmailAddresses.isValid("@maxipet.com")).isFalse();
        assertThat(EmailAddresses.isValid("ana@maxipet")).isFalse();   // sin TLD
        assertThat(EmailAddresses.isValid("ana@@maxipet.com")).isFalse();
        assertThat(EmailAddresses.isValid(null)).isFalse();
    }

    @Test
    void rechazaSaltosDeLinea() {
        // Hoy el transporte es JSON, así que una inyección de cabeceras no
        // aplica. Se rechaza igual para que el día que alguien cambie a SMTP
        // el dato no llegue crudo a construir cabeceras.
        assertThat(EmailAddresses.isValid("ana@maxipet.com\r\nBcc: otro@ajeno.com")).isFalse();
        assertThat(EmailAddresses.isValid("ana@maxipet.com\nX-Header: x")).isFalse();
    }

    @Test
    void rechazaDireccionesAbsurdamenteLargas() {
        String larga = "a".repeat(250) + "@maxipet.com";
        assertThat(EmailAddresses.isValid(larga)).isFalse();
    }

    @Test
    void enmascaraDejandoSoloLoJustoParaReconocerla() {
        assertThat(EmailAddresses.mask("ana@maxipet.com")).isEqualTo("an••••@maxipet.com");
        // Con un local part de un solo carácter no se puede mostrar dos sin
        // revelarlo entero.
        assertThat(EmailAddresses.mask("a@maxipet.com")).isEqualTo("a••••@maxipet.com");
        assertThat(EmailAddresses.mask(null)).isNull();
    }
}
