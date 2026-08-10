package com.biblioteca.service;

import com.biblioteca.model.KnownDevice;
import com.biblioteca.model.User;
import com.biblioteca.repository.KnownDeviceRepository;
import com.biblioteca.security.DeviceCookieFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cuándo sale el aviso de inicio de sesión y cuándo no.
 *
 * El objetivo del aviso es que alguien note el acceso que NO hizo. Eso se
 * pierde en dos direcciones: si no llega nunca, y si llega tan seguido que se
 * archiva sin leer. Los tests de acá fijan las dos.
 */
class LoginAlertServiceTest {

    private KnownDeviceRepository deviceRepository;
    private MailService mailService;
    private EmailTemplates templates;
    private LoginAlertService service;

    @BeforeEach
    void setUp() {
        deviceRepository = mock(KnownDeviceRepository.class);
        mailService = mock(MailService.class);
        templates = mock(EmailTemplates.class);
        DeviceCookieFactory cookieFactory = mock(DeviceCookieFactory.class);
        when(cookieFactory.newValue()).thenReturn("cookie-nueva");

        service = new LoginAlertService(deviceRepository, cookieFactory, mailService, templates);
    }

    private static User conCorreo(String preferencia) {
        User u = new User();
        u.setId(7);
        u.setUsername("ana");
        u.setEmail("ana@maxipet.com");
        u.setEmailVerified(true);
        u.setLoginAlerts(preferencia);
        return u;
    }

    // ─── Decisión de avisar ─────────────────────────────────────────────────

    @Test
    void modoNewDeviceSoloAvisaCuandoElDispositivoEsNuevo() {
        assertThat(LoginAlertService.shouldNotify("new_device", true)).isTrue();
        assertThat(LoginAlertService.shouldNotify("new_device", false)).isFalse();
    }

    @Test
    void modoAlwaysAvisaSiempreYModoOffNunca() {
        assertThat(LoginAlertService.shouldNotify("always", false)).isTrue();
        assertThat(LoginAlertService.shouldNotify("off", true)).isFalse();
    }

    @Test
    void unValorInesperadoNoAvisa() {
        // Un dato corrupto no debe traducirse en una ráfaga de correos.
        assertThat(LoginAlertService.shouldNotify("cualquier-cosa", true)).isFalse();
    }

    @Test
    void sinCorreoVerificadoNoSeAvisaAunqueLaPreferenciaDigaQueSi() {
        User u = conCorreo("always");
        u.setEmailVerified(false);

        service.notifyLogin(u, new LoginAlertService.DeviceCheck("c", true, "Chrome"), "1.1.1.1");

        verify(mailService, never()).sendAsync(anyString(), any());
    }

    @Test
    void conDispositivoNuevoYPreferenciaNewDeviceSeEnvia() {
        User u = conCorreo("new_device");

        service.notifyLogin(u, new LoginAlertService.DeviceCheck("c", true, "Chrome · Windows"), "1.1.1.1");

        verify(mailService).sendAsync(anyString(), any());
    }

    @Test
    void unFalloDelCorreoNoRompeElInicioDeSesion() {
        User u = conCorreo("always");
        org.mockito.Mockito.doThrow(new RuntimeException("proveedor caído"))
                .when(mailService).sendAsync(anyString(), any());

        // El usuario ya probó quién es: quedarse sin aviso es malo, quedarse
        // sin entrar es peor.
        service.notifyLogin(u, new LoginAlertService.DeviceCheck("c", true, "Chrome"), "1.1.1.1");
    }

    // ─── Registro de dispositivos ───────────────────────────────────────────

    @Test
    void unDispositivoYaConocidoNoEsNuevo() {
        KnownDevice existente = new KnownDevice();
        existente.setId(1L);
        existente.setLabel("Chrome · Windows");
        when(deviceRepository.findByUserIdAndDeviceHash(anyInt(), anyString()))
                .thenReturn(Optional.of(existente));

        var check = service.registerDevice(7, "cookie-vieja", "Mozilla Chrome/120", "1.1.1.1");

        assertThat(check.isNew()).isFalse();
        // La cookie se devuelve igual para renovarle el vencimiento: un usuario
        // activo no debe volver a "dispositivo nuevo" porque pasaron 400 días.
        assertThat(check.cookieValue()).isEqualTo("cookie-vieja");
        verify(deviceRepository).touch(any(), any(), anyString());
    }

    @Test
    void elPrimerDispositivoDeLaCuentaSeRegistraEnSilencio() {
        when(deviceRepository.findByUserIdAndDeviceHash(anyInt(), anyString())).thenReturn(Optional.empty());
        when(deviceRepository.existsByUserId(7)).thenReturn(false);

        var check = service.registerDevice(7, null, "Mozilla Chrome/120 Windows", "1.1.1.1");

        // Si contara como nuevo, activar el correo dispararía un aviso sobre la
        // sesión que el usuario tiene abierta en ese mismo momento.
        assertThat(check.isNew()).isFalse();
        verify(deviceRepository).save(any(KnownDevice.class));
    }

    @Test
    void unDispositivoDesconocidoEnUnaCuentaConHistorialSiEsNuevo() {
        when(deviceRepository.findByUserIdAndDeviceHash(anyInt(), anyString())).thenReturn(Optional.empty());
        when(deviceRepository.existsByUserId(7)).thenReturn(true);

        var check = service.registerDevice(7, null, "Mozilla Firefox/121 Android", "9.9.9.9");

        assertThat(check.isNew()).isTrue();
        assertThat(check.cookieValue()).isEqualTo("cookie-nueva");
    }

    @Test
    void unFalloDeBaseNoDisparaAvisosFalsos() {
        when(deviceRepository.findByUserIdAndDeviceHash(anyInt(), anyString()))
                .thenThrow(new RuntimeException("base caída"));

        var check = service.registerDevice(7, "cookie", "ua", "1.1.1.1");

        // Tratar el fallo como "dispositivo nuevo" mandaría una ráfaga de
        // avisos justo cuando el sistema ya está en problemas.
        assertThat(check.isNew()).isFalse();
    }

    // ─── Etiqueta del dispositivo ───────────────────────────────────────────

    @Test
    void laEtiquetaDistingueNavegadoresQueSeHacenPasarPorOtros() {
        // El UA de Edge contiene "Chrome" y "Safari"; el de Chrome contiene
        // "Safari". El orden de descarte es lo que hace que no se confundan.
        assertThat(LoginAlertService.deviceLabel(
                "Mozilla/5.0 (Windows NT 10.0) Chrome/120 Safari/537 Edg/120"))
                .isEqualTo("Edge · Windows");
        assertThat(LoginAlertService.deviceLabel(
                "Mozilla/5.0 (Macintosh; Mac OS X 10_15) Chrome/120 Safari/537"))
                .isEqualTo("Chrome · macOS");
        assertThat(LoginAlertService.deviceLabel(null))
                .isEqualTo("Dispositivo desconocido");
    }
}
