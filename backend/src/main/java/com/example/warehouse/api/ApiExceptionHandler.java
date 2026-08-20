package com.example.warehouse.api;

import com.example.warehouse.OperationsPausedException;
import com.example.warehouse.observability.RequestCorrelationFilter;
import com.example.warehouse.idempotency.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(OperationsPausedException.class)
  ResponseEntity<ProblemDetail> paused(OperationsPausedException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "Operations paused", exception.getMessage(), request);
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<ProblemDetail> idempotency(IdempotencyConflictException exception, HttpServletRequest request) {
    return problem(HttpStatus.CONFLICT, "Idempotency conflict", exception.getMessage(), request);
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<ProblemDetail> invalid(Exception exception, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "Invalid request", exception.getMessage(), request);
  }

  private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setType(URI.create("https://warehouse-visualizer.dev/problems/" + title.toLowerCase().replace(' ', '-')));
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("correlationId", request.getAttribute(RequestCorrelationFilter.MDC_KEY) == null
        ? org.slf4j.MDC.get(RequestCorrelationFilter.MDC_KEY) : request.getAttribute(RequestCorrelationFilter.MDC_KEY));
    return ResponseEntity.status(status).body(problem);
  }
}
