package com.jobtracker.exception;

import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        log.warn("event=RESOURCE_NOT_FOUND message={}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(BadRequestException ex) {
        log.warn("event=BAD_REQUEST message={}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), codeOf(ex));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(UnauthorizedException ex) {
        log.warn("event=UNAUTHORIZED message={}", ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage(), codeOf(ex));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ConflictException ex) {
        log.warn("event=CONFLICT message={}", ex.getMessage());
        return buildResponse(HttpStatus.CONFLICT, ex.getMessage(), codeOf(ex));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.warn("event=SERVICE_UNAVAILABLE message={}", ex.getMessage());
        return buildResponse(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), codeOf(ex));
    }

    // Content type is preset so the JSON body is written even when the client sent Accept: text/event-stream.
    @ExceptionHandler(AssistantQuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleAssistantQuota(AssistantQuotaExceededException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        body.put("message", ex.getMessage());
        body.put("code", ex.getCode());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.APPLICATION_JSON);
        if (ex.getRetryAfterSeconds() != null) {
            body.put("retryAfterSeconds", ex.getRetryAfterSeconds());
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        if (ex.getRetryAt() != null) body.put("retryAt", ex.getRetryAt().toString());
        return response.body(body);
    }

    @ExceptionHandler(IntegrationRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleIntegrationRateLimit(IntegrationRateLimitException ex) {
        ResponseEntity<Map<String, Object>> base = buildResponse(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), ex.getCode());
        if (ex.getRetryAfter() == null) {
            return base;
        }
        long seconds = Math.max(1, ex.getRetryAfter().toSeconds());
        base.getBody().put("retryAfterSeconds", seconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(base.getBody());
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<Map<String, Object>> handleBadCredentials(RuntimeException ex) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "Invalid credentials");
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<Map<String, Object>> handleRateLimitExceeded(RequestNotPermitted ex) {
        log.warn("event=RATE_LIMIT_EXCEEDED message={}", ex.getMessage());
        return buildResponse(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again later.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("event=ACCESS_DENIED message={}", ex.getMessage());
        return buildResponse(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(value = MethodArgumentNotValidException.class, produces = {
            MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            MediaType.APPLICATION_JSON_VALUE
    })
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        log.warn("event=VALIDATION_FAILURE fieldErrors={}", fieldErrors);
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", "Validation failed");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // Map an unsupported HTTP method to 405 (not 500). Without this, the generic Exception
    // handler below catches HttpRequestMethodNotSupportedException and returns 500 — e.g. a
    // GET /connect/register (DCR is POST-only) would surface as a server error to clients.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("event=METHOD_NOT_ALLOWED message={}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("error", "method_not_allowed");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    // Map an unparseable/malformed request body to 400 (not 500). Without this, the
    // generic Exception handler below catches HttpMessageNotReadableException (e.g. a
    // date that doesn't match the expected yyyy-MM-dd format) and returns 500.
    @ExceptionHandler(value = HttpMessageNotReadableException.class, produces = {
            MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            MediaType.APPLICATION_JSON_VALUE
    })
    public ResponseEntity<Map<String, Object>> handleNotReadable(HttpMessageNotReadableException ex) {
        log.warn("event=MALFORMED_REQUEST_BODY message={}", ex.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Malformed or unreadable request body");
    }

    @ExceptionHandler(value = Exception.class, produces = {
            MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            MediaType.APPLICATION_JSON_VALUE
    })
    public ResponseEntity<Map<String, Object>> handleGeneral(Exception ex) {
        log.error("event=UNEXPECTED_ERROR message={}", ex.getMessage(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public ResponseEntity<?> handleBrokenPipe(Exception ex, HttpServletResponse response) {
        log.warn("event=BROKEN_PIPE message={}", ex.getMessage());
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        return null; // No body since the client has disconnected
    }

    private String codeOf(Exception ex) {
        return ex instanceof CodedException coded ? coded.getCode() : null;
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
        return buildResponse(status, message, null);
    }

    private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        if (code != null) {
            body.put("code", code);
        }
        return ResponseEntity.status(status).body(body);
    }
}
