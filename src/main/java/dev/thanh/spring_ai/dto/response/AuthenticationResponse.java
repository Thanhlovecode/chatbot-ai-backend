package dev.thanh.spring_ai.dto.response;

/**
 * Login/refresh response DTO.
 * Only accessToken is returned in the response body.
 * refreshToken is set as an HttpOnly cookie by the controller.
 */
public record AuthenticationResponse(
        String accessToken
) {
}
