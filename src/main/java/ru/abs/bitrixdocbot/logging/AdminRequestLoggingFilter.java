package ru.abs.bitrixdocbot.logging;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class AdminRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminRequestLoggingFilter.class);
    private static final String OPERATION_HEADER = "X-Operation-Id";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.startsWith("/api/admin") || path.startsWith("/admin") || "/".equals(path));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String operationId = operationId(request.getHeader(OPERATION_HEADER));
        long started = System.nanoTime();
        MDC.put("operationId", operationId);
        response.setHeader(OPERATION_HEADER, operationId);

        String username = request.getUserPrincipal() == null ? "anonymous" : request.getUserPrincipal().getName();
        String clientIp = clientIp(request);
        boolean apiRequest = request.getRequestURI().startsWith("/api/admin");

        log(apiRequest, "ADMIN HTTP -> operationId={} method={} uri={} query={} user={} ip={}",
            operationId,
            request.getMethod(),
            request.getRequestURI(),
            safeQuery(request.getQueryString()),
            username,
            clientIp);

        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            log.error("ADMIN HTTP !! operationId={} method={} uri={} failed", operationId,
                request.getMethod(), request.getRequestURI(), exception);
            throw exception;
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log(apiRequest, "ADMIN HTTP <- operationId={} method={} uri={} status={} durationMs={}",
                operationId,
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                durationMs);
            MDC.remove("operationId");
        }
    }

    private void log(boolean info, String format, Object... arguments) {
        if (info) {
            log.info(format, arguments);
        } else {
            log.debug(format, arguments);
        }
    }

    private String operationId(String submitted) {
        if (submitted != null && submitted.matches("[A-Za-z0-9._-]{1,64}")) {
            return submitted;
        }
        return UUID.randomUUID().toString().substring(0, 12);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return LogSanitizer.shortValue(forwarded.split(",")[0], 80);
        }
        return request.getRemoteAddr();
    }

    private String safeQuery(String query) {
        return query == null || query.isBlank() ? "-" : LogSanitizer.shortValue(query, 160);
    }
}
