package dev.thanh.spring_ai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration properties for admin user seeding.
 * <p>
 * Reads {@code app.admin.emails} from environment variable {@code ADMIN_EMAILS}.
 * Supports comma-separated list of emails.
 * Example: {@code ADMIN_EMAILS=admin1@gmail.com,admin2@gmail.com}
 */
@Component
@ConfigurationProperties(prefix = "app.admin")
@Getter
@Setter
public class AdminProperties {

    /**
     * List of emails to be granted ADMIN role on application startup.
     * If empty, no admin seeding is performed.
     */
    private List<String> emails = List.of();
}
