package com.asa.pay.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HmacAuthenticationFilter extends OncePerRequestFilter {

    static final String SIGNATURE_HEADER = "X-Signature";
    static final String TIMESTAMP_HEADER = "X-Timestamp";
    private static final String PROTECTED_PREFIX = "/v1/pos/";

    private static final Logger log = LoggerFactory.getLogger(HmacAuthenticationFilter.class);

    private final HmacProperties properties;
    private final ObjectMapper objectMapper;

    public HmacAuthenticationFilter(HmacProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        String signature = request.getHeader(SIGNATURE_HEADER);
        String timestamp = request.getHeader(TIMESTAMP_HEADER);

        if (!StringUtils.hasText(signature) || !StringUtils.hasText(timestamp)) {
            reject(response, "Headers " + SIGNATURE_HEADER + " e " + TIMESTAMP_HEADER + " são obrigatórios");
            return;
        }

        long requestEpoch;
        try {
            requestEpoch = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            reject(response, "X-Timestamp inválido (esperado epoch em segundos)");
            return;
        }

        long skew = Math.abs(Instant.now().getEpochSecond() - requestEpoch);
        if (skew > properties.timestampToleranceSeconds()) {
            reject(response, "X-Timestamp fora da janela permitida (possível replay)");
            return;
        }

        String canonical = HmacSigner.canonicalString(
                timestamp.trim(),
                request.getMethod(),
                request.getRequestURI(),
                cachedRequest.getBodyAsString());
        String expected = HmacSigner.hexSignature(properties.secret(), canonical);

        if (!HmacSigner.constantTimeEquals(expected, signature.trim().toLowerCase())) {
            reject(response, "Assinatura HMAC inválida");
            return;
        }

        filterChain.doFilter(cachedRequest, response);
    }

    private void reject(HttpServletResponse response, String detail) throws IOException {
        log.warn("Request HMAC rejeitado: {}", detail);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, detail);
        problem.setTitle("Falha de autenticação HMAC");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
