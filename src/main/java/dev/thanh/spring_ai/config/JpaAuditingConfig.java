package dev.thanh.spring_ai.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Isolated JPA Auditing configuration.
 * Extracted from SpringAiApplication to prevent @WebMvcTest slice tests
 * from requiring jpaMappingContext (which caused BeanCreationException).
 *
 * See: https://github.com/spring-projects/spring-boot/issues/7079
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
