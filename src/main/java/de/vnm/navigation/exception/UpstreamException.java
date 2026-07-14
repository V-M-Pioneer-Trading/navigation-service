package de.vnm.navigation.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the SpaceTraders upstream returns an error.
 * The status code mirrors the upstream response where meaningful.
 */
public class UpstreamException extends RuntimeException {

    private final HttpStatus status;

    public UpstreamException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
