package com.biblioteca.exception;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public static ApiException badRequest(String msg)   { return new ApiException(HttpStatus.BAD_REQUEST, msg); }
    public static ApiException unauthorized(String msg) { return new ApiException(HttpStatus.UNAUTHORIZED, msg); }
    public static ApiException forbidden(String msg)    { return new ApiException(HttpStatus.FORBIDDEN, msg); }
    public static ApiException notFound(String msg)     { return new ApiException(HttpStatus.NOT_FOUND, msg); }

    /** Techos de emisión (cooldown entre reenvíos, códigos por hora). El 429 le
     *  dice al cliente "esperá", que es distinto de "esto está mal". */
    public static ApiException tooManyRequests(String msg) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, msg);
    }

    /** El correo no se pudo enviar o el envío está apagado — falla del sistema,
     *  no del usuario, así que no debe leerse como un dato inválido suyo. */
    public static ApiException unavailable(String msg) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, msg);
    }
}
