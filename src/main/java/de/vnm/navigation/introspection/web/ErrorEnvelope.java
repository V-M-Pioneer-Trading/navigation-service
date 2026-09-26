package de.vnm.navigation.introspection.web;

import de.vnm.navigation.introspection.Rejection;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes a {@link Rejection} as the family's {@code {"error":{"message":"…"}}} envelope,
 * byte for byte what every backend answers auth rejections with, so command-interface needs
 * one parser for all of them.
 *
 * <p>This is a deliberate exception to the RFC 9457 problem details this service emits
 * everywhere else (the fixture's {@code $messagesComment} records it). It is written
 * straight to the servlet response from the interceptor, before any controller runs and
 * without {@code sendError}, so neither {@code GlobalExceptionHandler} nor Spring Boot's
 * error page ever gets a chance to re-render it.
 */
final class ErrorEnvelope {

    private ErrorEnvelope() {}

    static void write(HttpServletResponse response, Rejection rejection) throws IOException {
        // The five sentences are fixed ASCII with nothing to escape; building the body by
        // hand keeps it byte-identical to what this service answered before decision 21.
        byte[] body = ("{\"error\":{\"message\":\"" + rejection.message() + "\"}}").getBytes(StandardCharsets.UTF_8);
        response.setStatus(rejection.status());
        response.setContentType("application/json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
