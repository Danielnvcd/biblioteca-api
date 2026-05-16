package com.biblioteca.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The legacy `/api/uploads/**` static handler was removed — it bypassed
 * FileStorageService path-traversal protection and almacenes role checks.
 * All file serving must go through FileController.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
}
