package com.northwood.shared.api.exception;

import com.northwood.shared.application.exception.BadRequestException;
import com.northwood.shared.application.exception.ConflictException;
import com.northwood.shared.application.exception.DomainException;
import com.northwood.shared.application.exception.NotFoundException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central {@link RestControllerAdvice} for every service: translates
 * application-layer {@link DomainException} subclasses into typed
 * {@link ErrorResponse} JSON bodies with the matching HTTP status. The
 * abstract base classes ({@link NotFoundException}, {@link ConflictException},
 * {@link BadRequestException}) each get their own handler — Spring's
 * {@code @ExceptionHandler} matches concrete subclasses to the closest
 * registered handler, so a concrete {@code CustomerNotFoundException
 * extends NotFoundException} routes through {@link #handleNotFound}.
 *
 * <p>Auto-wired via {@code DomainExceptionAutoConfiguration} into every
 * service that has a web stack; no per-controller {@code @ExceptionHandler}
 * methods are needed for {@code DomainException} subclasses any more.
 *
 * <p><b>Fallback handlers for raw {@link IllegalArgumentException} /
 * {@link IllegalStateException}.</b> These exist for the case where an
 * {@code Assert.argument(...)} / {@code Assert.state(...)} call inside a
 * service method fires for a precondition the controller didn't pre-check.
 * The response carries a generic {@code GENERIC_ARGUMENT_VIOLATION} /
 * {@code GENERIC_STATE_VIOLATION} code with the exception's English message
 * in the {@code detail} param — useful as a fallback, but the better
 * shape for any check the SPA needs to dispatch on is to promote it to
 * a dedicated {@link BadRequestException} / {@link ConflictException}
 * subclass with its own {@code CODE}.
 */
@RestControllerAdvice
public class DomainExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(DomainExceptionAdvice.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException e) {
        return respond(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException e) {
        return respond(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException e) {
        return respond(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Untyped IllegalArgumentException reached the wire — consider promoting "
            + "to a BadRequestException subclass with a dedicated CODE: {}", e.getMessage());
        return ResponseEntity.badRequest().body(
            new ErrorResponse("GENERIC_ARGUMENT_VIOLATION", Map.of("detail", e.getMessage()))
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        log.warn("Untyped IllegalStateException reached the wire — consider promoting "
            + "to a ConflictException subclass with a dedicated CODE: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            new ErrorResponse("GENERIC_STATE_VIOLATION", Map.of("detail", e.getMessage()))
        );
    }

    private <T extends RuntimeException & DomainException> ResponseEntity<ErrorResponse> respond(
        HttpStatus status, T e
    ) {
        log.debug("[{}] {} → {} ({})", status.value(), e.code(), e.getMessage(), e.params());
        return ResponseEntity.status(status).body(new ErrorResponse(e.code(), e.params()));
    }
}
