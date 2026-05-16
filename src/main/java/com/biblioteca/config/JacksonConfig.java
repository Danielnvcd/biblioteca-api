package com.biblioteca.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Treats empty strings as null for LocalDate / LocalDateTime fields.
 * The frontend sends `fecha: ""` for unfilled date inputs which would otherwise fail to parse.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public JavaTimeModule javaTimeModule() {
        JavaTimeModule mod = new JavaTimeModule();
        mod.addDeserializer(LocalDate.class, new EmptyAsNullLocalDateDeserializer());
        mod.addDeserializer(LocalDateTime.class, new EmptyAsNullLocalDateTimeDeserializer());
        return mod;
    }

    @Bean
    public SimpleModule emptyStringModule() {
        return new SimpleModule();
    }

    static class EmptyAsNullLocalDateDeserializer extends JsonDeserializer<LocalDate> {
        private final LocalDateDeserializer delegate =
                new LocalDateDeserializer(DateTimeFormatter.ISO_LOCAL_DATE);
        @Override
        public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) return null;
            return delegate.deserialize(p, ctxt);
        }
    }

    static class EmptyAsNullLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        private final LocalDateTimeDeserializer delegate = LocalDateTimeDeserializer.INSTANCE;
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String text = p.getText();
            if (text == null || text.isBlank()) return null;
            return delegate.deserialize(p, ctxt);
        }
    }
}
