package dev.thanh.spring_ai.service.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import dev.thanh.spring_ai.dto.response.GoogleUserInfo;
import dev.thanh.spring_ai.enums.SecurityErrorCode;
import dev.thanh.spring_ai.exception.SecurityAuthException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GoogleTokenVerifierService unit tests — ensures:
 * 1. Valid Google token → correct GoogleUserInfo extraction
 * 2. Invalid/null token → SecurityAuthException(GOOGLE_TOKEN_INVALID)
 * 3. Unverified email → SecurityAuthException(EMAIL_NOT_VERIFIED)
 * 4. Network errors → SecurityAuthException(GOOGLE_TOKEN_INVALID)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleTokenVerifierService — Unit Tests")
class GoogleTokenVerifierServiceTest {

    @Mock
    private GoogleIdTokenVerifier verifier;

    @InjectMocks
    private GoogleTokenVerifierService googleTokenVerifierService;

    // ═══════════════════════════════════════════════════════════════════════
    // Happy Path
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("verifyAndExtract — happy path")
    class HappyPathTests {

        @Test
        @DisplayName("valid Google token — should return GoogleUserInfo with all fields")
        void validToken_ShouldReturnUserInfo() throws GeneralSecurityException, IOException {
            // Given
            GoogleIdToken idToken = mock(GoogleIdToken.class);
            Payload payload = new Payload();
            payload.setSubject("google-sub-123");
            payload.setEmail("user@gmail.com");
            payload.setEmailVerified(true);
            payload.set("name", "John Doe");
            payload.set("picture", "https://google.com/photo.jpg");

            when(verifier.verify("valid-id-token")).thenReturn(idToken);
            when(idToken.getPayload()).thenReturn(payload);

            // When
            GoogleUserInfo result = googleTokenVerifierService.verifyAndExtract("valid-id-token");

            // Then
            assertThat(result.getGoogleId()).isEqualTo("google-sub-123");
            assertThat(result.getEmail()).isEqualTo("user@gmail.com");
            assertThat(result.getEmailVerified()).isTrue();
            assertThat(result.getName()).isEqualTo("John Doe");
            assertThat(result.getPicture()).isEqualTo("https://google.com/photo.jpg");
        }

        @Test
        @DisplayName("valid token without picture — should return null picture")
        void validTokenNoPicture_ShouldReturnNullPicture() throws GeneralSecurityException, IOException {
            // Given
            GoogleIdToken idToken = mock(GoogleIdToken.class);
            Payload payload = new Payload();
            payload.setSubject("google-sub-456");
            payload.setEmail("nopic@gmail.com");
            payload.setEmailVerified(true);
            payload.set("name", "No Picture");
            // No picture set

            when(verifier.verify("no-pic-token")).thenReturn(idToken);
            when(idToken.getPayload()).thenReturn(payload);

            // When
            GoogleUserInfo result = googleTokenVerifierService.verifyAndExtract("no-pic-token");

            // Then
            assertThat(result.getPicture()).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Invalid Token
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("verifyAndExtract — invalid token")
    class InvalidTokenTests {

        @Test
        @DisplayName("null verifier result — should throw GOOGLE_TOKEN_INVALID")
        void nullVerifierResult_ShouldThrow() throws GeneralSecurityException, IOException {
            when(verifier.verify("bad-token")).thenReturn(null);

            assertThatThrownBy(() -> googleTokenVerifierService.verifyAndExtract("bad-token"))
                    .isInstanceOf(SecurityAuthException.class)
                    .satisfies(ex -> assertThat(((SecurityAuthException) ex).getErrorCode())
                            .isEqualTo(SecurityErrorCode.GOOGLE_TOKEN_INVALID));
        }

        @Test
        @DisplayName("GeneralSecurityException — should throw GOOGLE_TOKEN_INVALID with cause")
        void securityException_ShouldThrow() throws GeneralSecurityException, IOException {
            when(verifier.verify("tampered-token"))
                    .thenThrow(new GeneralSecurityException("Signature mismatch"));

            assertThatThrownBy(() -> googleTokenVerifierService.verifyAndExtract("tampered-token"))
                    .isInstanceOf(SecurityAuthException.class)
                    .satisfies(ex -> assertThat(((SecurityAuthException) ex).getErrorCode())
                            .isEqualTo(SecurityErrorCode.GOOGLE_TOKEN_INVALID))
                    .hasCauseInstanceOf(GeneralSecurityException.class);
        }

        @Test
        @DisplayName("IOException (network failure) — should throw GOOGLE_TOKEN_INVALID")
        void ioException_ShouldThrow() throws GeneralSecurityException, IOException {
            when(verifier.verify("network-fail-token"))
                    .thenThrow(new IOException("Connection refused to Google API"));

            assertThatThrownBy(() -> googleTokenVerifierService.verifyAndExtract("network-fail-token"))
                    .isInstanceOf(SecurityAuthException.class)
                    .satisfies(ex -> assertThat(((SecurityAuthException) ex).getErrorCode())
                            .isEqualTo(SecurityErrorCode.GOOGLE_TOKEN_INVALID))
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Email Not Verified
    // ═══════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("verifyAndExtract — email verification")
    class EmailVerificationTests {

        @Test
        @DisplayName("email not verified — should throw EMAIL_NOT_VERIFIED (403)")
        void emailNotVerified_ShouldThrow() throws GeneralSecurityException, IOException {
            GoogleIdToken idToken = mock(GoogleIdToken.class);
            Payload payload = new Payload();
            payload.setSubject("google-sub");
            payload.setEmail("unverified@gmail.com");
            payload.setEmailVerified(false); // NOT verified
            payload.set("name", "Unverified User");

            when(verifier.verify("unverified-token")).thenReturn(idToken);
            when(idToken.getPayload()).thenReturn(payload);

            assertThatThrownBy(() -> googleTokenVerifierService.verifyAndExtract("unverified-token"))
                    .isInstanceOf(SecurityAuthException.class)
                    .satisfies(ex -> assertThat(((SecurityAuthException) ex).getErrorCode())
                            .isEqualTo(SecurityErrorCode.EMAIL_NOT_VERIFIED));
        }

        @Test
        @DisplayName("email verified = true — should proceed normally")
        void emailVerified_ShouldProceed() throws GeneralSecurityException, IOException {
            GoogleIdToken idToken = mock(GoogleIdToken.class);
            Payload payload = new Payload();
            payload.setSubject("google-sub");
            payload.setEmail("verified@gmail.com");
            payload.setEmailVerified(true);
            payload.set("name", "Verified User");

            when(verifier.verify("verified-token")).thenReturn(idToken);
            when(idToken.getPayload()).thenReturn(payload);

            GoogleUserInfo result = googleTokenVerifierService.verifyAndExtract("verified-token");
            assertThat(result.getEmail()).isEqualTo("verified@gmail.com");
        }
    }
}
