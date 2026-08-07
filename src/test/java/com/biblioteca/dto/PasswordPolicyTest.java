package com.biblioteca.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La política de contraseñas tiene que ser la misma en los dos puntos donde se
 * fija una: el alta (RegisterRequest) y el cambio (ChangePasswordRequest).
 *
 * Antes solo el alta exigía letra + número; el cambio pedía únicamente 8
 * caracteres. O sea que el punto de control más usado era el más laxo, y una
 * cuenta creada con una contraseña aceptable podía degradarse a "12345678".
 */
class PasswordPolicyTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void init() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static ChangePasswordRequest cambioA(String nueva) {
        ChangePasswordRequest r = new ChangePasswordRequest();
        r.setCurrentPassword("laActual1");
        r.setNewPassword(nueva);
        return r;
    }

    private static RegisterRequest altaCon(String password) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername("nuevo");
        r.setPassword(password);
        return r;
    }

    @Test
    void elCambioRechazaContraseniaSoloNumerica() {
        assertThat(validator.validate(cambioA("12345678"))).isNotEmpty();
    }

    @Test
    void elCambioRechazaContraseniaSoloAlfabetica() {
        assertThat(validator.validate(cambioA("contrasenia"))).isNotEmpty();
    }

    @Test
    void elCambioRechazaContraseniaCorta() {
        assertThat(validator.validate(cambioA("abc123"))).isNotEmpty();
    }

    @Test
    void elCambioAceptaLetraMasNumero() {
        assertThat(validator.validate(cambioA("segura2026"))).isEmpty();
    }

    @Test
    void altaYCambioAceptanYRechazanLoMismo() {
        // El invariante que importa: si las dos políticas divergen otra vez,
        // este test lo detecta sin depender de recordar ambos sitios.
        for (String candidata : new String[]{
                "12345678", "contrasenia", "abc123", "segura2026", "A1bcdefg"}) {
            boolean altaOk   = validator.validate(altaCon(candidata)).isEmpty();
            boolean cambioOk = validator.validate(cambioA(candidata)).isEmpty();
            assertThat(cambioOk)
                    .as("'%s' debe ser aceptada/rechazada igual en alta y cambio", candidata)
                    .isEqualTo(altaOk);
        }
    }
}
