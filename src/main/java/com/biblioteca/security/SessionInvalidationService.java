package com.biblioteca.security;

import com.biblioteca.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Loads the current security-relevant user state for the JWT filter.
 */
@Service
public class SessionInvalidationService {

    private static final Logger log = LoggerFactory.getLogger(SessionInvalidationService.class);

    private final UserRepository userRepository;

    public SessionInvalidationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<UserSecurityState> findSecurityState(Integer userId) {
        return userRepository.findSecurityStateById(userId);
    }

    /**
     * Cada cuánto se refresca last_seen. Es el margen de error del indicador
     * "en línea": con 2 minutos y un umbral de 5 en el frontend, alguien activo
     * nunca aparece desconectado, y quien se fue cae del indicador en ~5.
     *
     * Bajarlo mejora la precisión a costa de un UPDATE más seguido por usuario
     * activo; subirlo hace que el umbral del frontend tenga que crecer igual.
     */
    private static final Duration ACTIVITY_THROTTLE = Duration.ofMinutes(2);

    /**
     * Registra que el usuario está activo, si pasó suficiente tiempo desde la
     * última marca.
     *
     * Se llama desde el filtro de autenticación, así que CUALQUIER request
     * cuenta como actividad. Antes esto solo pasaba en /auth/me, que el
     * frontend llama una vez al arrancar: last_seen terminaba contando "cuándo
     * recargó la página" en vez de "cuándo estuvo activo", y cualquiera que
     * trabajara sin apretar F5 figuraba desconectado a los 5 minutos.
     *
     * El throttle usa el last_seen que ya viene en el snapshot, así que no
     * agrega ninguna consulta: en el peor caso, un UPDATE de una columna cada
     * dos minutos por usuario activo.
     */
    public void markActive(UserSecurityState state) {
        if (state == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (state.lastSeen() != null
                && state.lastSeen().isAfter(now.minus(ACTIVITY_THROTTLE))) {
            return;
        }
        try {
            userRepository.touchLastSeen(state.id(), now);
        } catch (RuntimeException ex) {
            // Un indicador de presencia no justifica tumbar un request. Si el
            // UPDATE falla (deadlock, BD saturada), se pierde una marca y la
            // siguiente request la vuelve a intentar.
            log.debug("No se pudo actualizar last_seen de userId={}", state.id(), ex);
        }
    }

    /** Millisecond precision avoids same-second gaps when passwords are changed. */
    public long passwordChangedEpochMillis(UserSecurityState state) {
        if (state == null || state.passwordChangedAt() == null) {
            return 0L;
        }
        return state.passwordChangedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
