package com.eventledger.gateway.exception;

import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.config.LedgerMetrics;
import com.eventledger.gateway.dto.ErrorResponse;
import com.eventledger.gateway.dto.EventRequest;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final ObjectProvider<Tracer> tracerProvider;
    private final ObjectProvider<RateLimiterRegistry> rateLimiterRegistryProvider;
    private final LedgerMetrics metrics;

    public ApiExceptionHandler(ObjectProvider<Tracer> tracerProvider,
                               ObjectProvider<RateLimiterRegistry> rateLimiterRegistryProvider,
                               LedgerMetrics metrics) {
        this.tracerProvider = tracerProvider;
        this.rateLimiterRegistryProvider = rateLimiterRegistryProvider;
        this.metrics = metrics;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        if (ex.getBindingResult().getTarget() instanceof EventRequest request
                && request.type() != null) {
            metrics.eventSubmitted(request.type(), "rejected");
        }
        List<ErrorResponse.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "Validation failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        List<ErrorResponse.FieldError> details =
                List.of(new ErrorResponse.FieldError(ex.getParameterName(), "query parameter is required"));
        return build(HttpStatus.BAD_REQUEST, "Missing required parameter", details);
    }

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEventNotFound(EventNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(AccountNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), null);
    }

    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUnavailable(AccountServiceUnavailableException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), null);
    }

    /** Circuit open: fail fast with the same contract as an unreachable downstream. */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> handleCircuitOpen(CallNotPermittedException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Account Service is unreachable", null);
    }

    /** Bulkhead full: local back-pressure, shed with 503; safe for the client to retry. */
    @ExceptionHandler(BulkheadFullException.class)
    public ResponseEntity<ErrorResponse> handleBulkheadFull(BulkheadFullException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Account Service is overloaded", null);
    }

    /**
     * Inbound rate limit exceeded: 429 with rate-limit headers so clients can
     * back off instead of blind-retrying. Retry-After / X-RateLimit-Reset are
     * one refresh window — an upper bound, since RequestNotPermitted itself
     * carries no timing and the window may refresh sooner.
     */
    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<ErrorResponse> handleRateLimited(RequestNotPermitted ex) {
        ErrorResponse body = new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(),
                HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "Too many requests, slow down", currentTraceId(), Instant.now(), null);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS);

        RateLimiterRegistry registry = rateLimiterRegistryProvider.getIfAvailable();
        if (registry != null) {
            var limiter = registry.rateLimiter(ex.getCausingRateLimiterName());
            long resetSeconds = Math.max(1,
                    limiter.getRateLimiterConfig().getLimitRefreshPeriod().toSeconds());
            response.header("Retry-After", String.valueOf(resetSeconds))
                    .header("X-RateLimit-Limit",
                            String.valueOf(limiter.getRateLimiterConfig().getLimitForPeriod()))
                    .header("X-RateLimit-Remaining",
                            String.valueOf(Math.max(0, limiter.getMetrics().getAvailablePermissions())))
                    .header("X-RateLimit-Reset", String.valueOf(resetSeconds));
        } else {
            response.header("Retry-After", "1");
        }
        return response.body(body);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                List<ErrorResponse.FieldError> details) {
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(),
                message, currentTraceId(), Instant.now(), details);
        return ResponseEntity.status(status).body(body);
    }

    private String currentTraceId() {
        Tracer tracer = tracerProvider.getIfAvailable();
        if (tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
