package de.vnm.navigation.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps the two exception families this service raises onto RFC 9457 problem details.
 * Anything else is left to Spring's default handling, which is a 500 — that is the
 * intent: an unmapped exception is a bug, not a documented API outcome.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Returns a {@code ResponseEntity} rather than a bare {@code ProblemDetail} so an
     * upstream's pacing headers can travel with it. st-gateway forwards
     * {@code Retry-After} and the {@code X-RateLimit-*} trio on a passed-through 429
     * precisely so a caller can pace itself; relaying the status without them keeps the
     * news and drops the instructions.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        log.warn("Request failed [{}]: {}", ex.getStatus(), ex.getMessage());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus());
        ex.getHeaders().forEach(response::header);
        return response.body(ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        log.warn("Rejected request: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
