package dev.thanh.spring_ai.utils;

import dev.thanh.spring_ai.config.security.CustomAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Utility class for accessing security context information.
 */
public final class SecurityUtils {

    private SecurityUtils() {
        // Utility class
    }

    /**
     * Get the current authenticated user's UUID from SecurityContext.
     *
     * @return UUID of the authenticated user
     * @throws ClassCastException if the authentication is not a CustomAuthenticationToken
     */
    public static UUID getCurrentUserId() {
        CustomAuthenticationToken authentication = (CustomAuthenticationToken)
                SecurityContextHolder.getContext().getAuthentication();
        return authentication.getUserId();
    }

    /**
     * Sanitize input for logging to prevent CRLF injection.
     */
    public static String sanitizeLog(String input) {
        if (input == null) {
            return "null";
        }
        return input.replaceAll("[\r\n\t]", "_");
    }
}
