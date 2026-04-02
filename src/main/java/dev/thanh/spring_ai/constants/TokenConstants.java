package dev.thanh.spring_ai.constants;

/**
 * Constants for JWT token Redis key prefixes and claim names.
 */
public final class TokenConstants {

    private TokenConstants() {
        // Utility class
    }

    // Redis key prefixes for token management
    public static final String AT_BLACK_LIST = "ACCESS_TOKEN_BLACK_LIST:";
    public static final String AT_WHITE_LIST = "ACCESS_TOKEN_WHITE_LIST:";
    public static final String RT_WHITE_LIST = "REFRESH_TOKEN_WHITE_LIST:";
    public static final String RT_BLACK_LIST = "REFRESH_TOKEN_BLACK_LIST:";

    // JWT claim names
    public static final String CLAIM_USER_ID = "user_id";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TOKEN_TYPE = "token_type";

    // Token types
    public static final String TOKEN_TYPE_ACCESS = "ACCESS";
    public static final String TOKEN_TYPE_REFRESH = "REFRESH";

    // Redis valid marker
    public static final String VALID_STATUS = "1";
}
