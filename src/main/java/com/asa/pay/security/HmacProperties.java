package com.asa.pay.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "security.hmac")
public record HmacProperties(
        boolean enabled,
        String secret,
        long timestampToleranceSeconds
) {
}
