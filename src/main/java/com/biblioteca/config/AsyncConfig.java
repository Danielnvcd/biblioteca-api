package com.biblioteca.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Ejecución en segundo plano para los avisos por correo, más el scheduler que
 * limpia los códigos vencidos.
 *
 * El executor es propio y no el default de Spring a propósito: el default es
 * un SimpleAsyncTaskExecutor que crea un thread NUEVO por tarea y no tiene
 * techo. Con un proveedor de correo lento, una ráfaga de inicios de sesión
 * abriría un thread por cada uno hasta tumbar la JVM — el aviso de login es
 * justamente lo que más se dispara cuando algo raro está pasando.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Pool EXCLUSIVO de los correos, con cola propia y acotada.
     *
     * Está aislado a propósito: si compartiera pool con cualquier otra tarea de
     * fondo, un proveedor de correo lento llenaría la cola común y frenaría
     * trabajo que no tiene nada que ver — o al revés.
     */
    @Bean(name = "mailExecutor")
    public Executor mailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mail-");
        // DiscardPolicy y no CallerRuns: si la cola de 200 se llena es porque el
        // proveedor está caído o degradado, y CallerRuns devolvería la lentitud
        // al thread de Tomcat que atiende el login — exactamente lo que este
        // executor existe para evitar. Se pierde el aviso y se deja constancia.
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("Cola de correo llena — se descarta un envío en segundo plano"));
        // Espera acotada al apagar: da tiempo a que salgan los avisos en vuelo
        // sin dejar el contenedor colgado si el proveedor no responde.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }

    /**
     * Repone el executor por defecto de Spring Boot.
     *
     * Sin esto habría una trampa esperando al próximo que agregue trabajo
     * asíncrono: el {@code applicationTaskExecutor} que Boot autoconfigura está
     * anotado {@code @ConditionalOnMissingBean(Executor.class)}, así que el
     * bean de arriba lo hacía desaparecer. Con un solo Executor en el
     * contenedor, un {@code @Async} SIN calificador resolvería contra el pool
     * de los correos y una tarea ajena terminaría compitiendo por (y llenando)
     * esa cola — un acoplamiento invisible que solo se manifiesta bajo carga.
     *
     * Declarándolo explícitamente vuelven a existir los dos, cada
     * {@code @Async} cae donde corresponde, y el de Boot crea sus hilos por
     * demanda: si nadie lo usa, no cuesta nada.
     */
    @Bean(name = "applicationTaskExecutor")
    public ThreadPoolTaskExecutor applicationTaskExecutor(ThreadPoolTaskExecutorBuilder builder) {
        return builder.build();
    }
}
