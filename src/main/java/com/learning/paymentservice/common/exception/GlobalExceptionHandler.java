package com.learning.paymentservice.common.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns every exception into an RFC 9457 {@code application/problem+json} body:
 *
 * <pre>
 * { "type": "about:blank", "title": "Conflict", "status": 409,
 *   "detail": "Payment … cannot move from COMPLETED to PENDING",
 *   "instance": "/api/v1/payments/…/status",
 *   "code": "INVALID_STATUS_TRANSITION", "timestamp": "…", …details }
 * </pre>
 *
 * Every response carries a stable {@code code}. Spring MVC's own exceptions (bad
 * JSON, wrong media type, unknown URL…) come from {@link ResponseEntityExceptionHandler};
 * this class adds field-level validation errors, business errors and a safe 500.
 * Internal details (SQL, stack traces, class names) never reach the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";
    static final String CONCURRENT_UPDATE = "CONCURRENT_UPDATE";
    static final String DATA_CONFLICT = "DATA_CONFLICT";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** One invalid field (or object-level rule, where {@code field} is the object name). */
    public record FieldViolation(String field, String message) {
    }

    // ---- Business errors -------------------------------------------------------

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusinessException(BusinessException ex, WebRequest request) {
        HttpStatus status = toHttpStatus(ex.getCategory());
        log.info("{} {}: {}", status.value(), ex.getCode(), ex.getMessage());
        ProblemDetail problem = problem(status, ex.getMessage(), ex.getCode(), request);
        ex.getDetails().forEach(problem::setProperty);
        return problem;
    }

    static HttpStatus toHttpStatus(ErrorCategory category) {
        return switch (category) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case UNPROCESSABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }

    // ---- Persistence races -----------------------------------------------------

    /** Two requests changed the same payment at once; the later one lost. Safe to retry after re-reading. */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex, WebRequest request) {
        log.info("409 {}: {}", CONCURRENT_UPDATE, ex.getMessage());
        return problem(HttpStatus.CONFLICT,
                "The payment was changed by another request. Fetch it again and retry.", CONCURRENT_UPDATE, request);
    }

    /**
     * A database constraint rejected the write, normally a unique key hit by two
     * simultaneous creates that both passed the service's checks. The SQL message
     * is logged, never returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        log.warn("409 {}: {}", DATA_CONFLICT, ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT,
                "The request conflicts with existing data, for example a reference or Idempotency-Key that is already used.",
                DATA_CONFLICT, request);
    }

    // ---- Safety net ------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception for {}", path(request), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.", INTERNAL_ERROR, request);
    }

    // ---- Spring MVC exceptions, refined ----------------------------------------

    /** {@code @Valid} failed on a request body or on query parameters. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> errors = Stream.concat(
                        ex.getBindingResult().getFieldErrors().stream().map(GlobalExceptionHandler::toViolation),
                        ex.getBindingResult().getGlobalErrors().stream().map(GlobalExceptionHandler::toViolation))
                .sorted((a, b) -> a.field().compareTo(b.field()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed", VALIDATION_FAILED, request);
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Method-level validation failed. Spring uses this path (instead of
     * {@link MethodArgumentNotValidException}) when a handler has a constraint on a
     * plain parameter, such as {@code @Size} on the Idempotency-Key header. The
     * {@code @Valid} body's field errors then arrive here too, as {@link ParameterErrors}.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<FieldViolation> errors = ex.getParameterValidationResults().stream()
                .flatMap(GlobalExceptionHandler::toViolations)
                .sorted((a, b) -> a.field().compareTo(b.field()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Request validation failed", VALIDATION_FAILED, request);
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** The body is not valid JSON, or a value has the wrong type (e.g. an unknown enum constant). */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        String detail = "The request body could not be read. Check that it is valid JSON.";
        if (ex.getCause() instanceof InvalidFormatException invalid) {
            String field = invalid.getPath().stream()
                    .map(JsonMappingException.Reference::getFieldName)
                    .collect(Collectors.joining("."));
            detail = "Invalid value '%s' for '%s'.".formatted(invalid.getValue(), field);
            if (invalid.getTargetType() != null && invalid.getTargetType().isEnum()) {
                detail += " Allowed values: " + Arrays.toString(invalid.getTargetType().getEnumConstants()) + ".";
            }
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, detail, MALFORMED_REQUEST, request);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /** A path variable or query parameter could not be converted, e.g. a payment id that is not a UUID. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : ex.getPropertyName();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST,
                "Invalid value '%s' for '%s'.".formatted(ex.getValue(), name), MALFORMED_REQUEST, request);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Every response built by {@link ResponseEntityExceptionHandler} passes through
     * here. Fills in {@code code}, {@code instance} and {@code timestamp} where the
     * default Spring handlers (405, 415, 404 for unknown URLs…) left them out.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        if (body == null && ex instanceof ErrorResponse errorResponse) {
            // Spring passes null for its own exceptions and builds the body later; build it now so we can enrich it.
            body = errorResponse.updateAndGetBody(getMessageSource(), LocaleContextHolder.getLocale());
        }
        if (body instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey("code")) {
                HttpStatus status = HttpStatus.resolve(statusCode.value());
                problem.setProperty("code", status != null ? status.name() : "HTTP_" + statusCode.value());
            }
            if (problem.getInstance() == null) {
                problem.setInstance(URI.create(path(request)));
            }
            if (!problem.getProperties().containsKey("timestamp")) {
                problem.setProperty("timestamp", Instant.now());
            }
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    // ---- Helpers ---------------------------------------------------------------

    private static ProblemDetail problem(HttpStatus status, String detail, String code, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(path(request)));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private static FieldViolation toViolation(FieldError error) {
        String message = error.isBindingFailure()
                ? "has an invalid value '%s'".formatted(error.getRejectedValue())
                : error.getDefaultMessage();
        return new FieldViolation(error.getField(), message);
    }

    private static FieldViolation toViolation(ObjectError error) {
        return new FieldViolation(error.getObjectName(), error.getDefaultMessage());
    }

    /** A {@code @Valid} object yields one violation per field; a plain parameter yields one per constraint. */
    private static Stream<FieldViolation> toViolations(ParameterValidationResult result) {
        if (result instanceof ParameterErrors errors) {
            return Stream.concat(
                    errors.getFieldErrors().stream().map(GlobalExceptionHandler::toViolation),
                    errors.getGlobalErrors().stream().map(GlobalExceptionHandler::toViolation));
        }
        String name = parameterName(result.getMethodParameter());
        return result.getResolvableErrors().stream().map(error -> new FieldViolation(name, error.getDefaultMessage()));
    }

    private static String parameterName(org.springframework.core.MethodParameter parameter) {
        var header = parameter.getParameterAnnotation(org.springframework.web.bind.annotation.RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        return parameter.getParameterName() != null ? parameter.getParameterName() : "arg" + parameter.getParameterIndex();
    }

    private static String path(WebRequest request) {
        if (request instanceof ServletWebRequest servlet) {
            return servlet.getRequest().getRequestURI();
        }
        return "";
    }
}
