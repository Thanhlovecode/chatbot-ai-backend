package dev.thanh.spring_ai.dto.response;

import lombok.Builder;
import lombok.Getter;

/**
 * DTO chứa thông tin user trích xuất từ Google ID Token.
 */
@Getter
@Builder
public class GoogleUserInfo {
    private String email;
    private String name;
    private String picture;
    private String googleId;
    private Boolean emailVerified;
}
