package dev.thanh.spring_ai.test;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration riêng cho Load Testing.
 * <p>
 * Tạo một SecurityFilterChain riêng biệt (Order=0 — ưu tiên cao nhất)
 * chỉ match pattern "/api/test/**" và permitAll().
 * <p>
 * ⚠️ CHỈ ACTIVE khi profile=test. Trên production, config này
 * KHÔNG tồn tại → /api/test/** yêu cầu JWT như bình thường
 * (và controller cũng không tồn tại → 404).
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
public class LoadTestSecurityConfig {

    @Bean
    @Order(0)  // Ưu tiên cao hơn SecurityFilterChain chính (default order = last)
    public SecurityFilterChain loadTestFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/test/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
