package dev.thanh.spring_ai.service.security;

import dev.thanh.spring_ai.dto.response.GoogleUserInfo;
import dev.thanh.spring_ai.enums.SecurityErrorCode;
import dev.thanh.spring_ai.exception.SecurityAuthException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Service responsible for verifying Google ID Tokens server-side.
 * Validates token signature, audience, issuer, and email verification status.
 */
@Service
@Slf4j(topic = "GOOGLE-TOKEN-VERIFIER")
@RequiredArgsConstructor
public class GoogleTokenVerifierService {

    private final GoogleIdTokenVerifier verifier;

    /**
     * Verify and extract user info from a Google ID Token.
     *
     * @param idTokenString the raw ID token string from the client
     * @return extracted GoogleUserInfo
     * @throws SecurityAuthException if token is invalid or email not verified
     */
    public GoogleUserInfo verifyAndExtract(String idTokenString) {
        GoogleIdToken idToken = verifyToken(idTokenString);
        Payload payload = idToken.getPayload();

        validateEmailVerified(payload);

        GoogleUserInfo userInfo = extractUserInfo(payload);
        log.info("✅ Verified Google user: {}", userInfo.getEmail());
        return userInfo;
    }

    private GoogleIdToken verifyToken(String idTokenString) {
        try {
            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                throw new SecurityAuthException(SecurityErrorCode.GOOGLE_TOKEN_INVALID);
            }
            return idToken;
        } catch (GeneralSecurityException | IOException e) {
            log.error("Google token verification failed", e);
            throw new SecurityAuthException(SecurityErrorCode.GOOGLE_TOKEN_INVALID, e);
        }
    }

    private void validateEmailVerified(Payload payload) {
        if (Boolean.FALSE.equals(payload.getEmailVerified())) {
            throw new SecurityAuthException(SecurityErrorCode.EMAIL_NOT_VERIFIED);
        }
    }

    private GoogleUserInfo extractUserInfo(Payload payload) {
        return GoogleUserInfo.builder()
                .googleId(payload.getSubject())
                .email(payload.getEmail())
                .emailVerified(payload.getEmailVerified())
                .name((String) payload.get("name"))
                .picture((String) payload.get("picture"))
                .build();
    }
}
