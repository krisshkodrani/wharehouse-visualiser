package com.example.warehouse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
class RequestCorrelationFilter extends OncePerRequestFilter {
  static final String HEADER = "X-Correlation-ID";
  static final String MDC_KEY = "correlationId";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = request.getHeader(HEADER);
    String correlationId = supplied == null || supplied.isBlank() ? UUID.randomUUID().toString() : supplied.trim();
    if (correlationId.length() > 128) correlationId = correlationId.substring(0, 128);
    response.setHeader(HEADER, correlationId);
    try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, correlationId)) {
      chain.doFilter(request, response);
    }
  }
}
