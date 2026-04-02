package dev.thanh.spring_ai.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SessionErrorCode {

    SESSION_NOT_FOUND(404, "Session không tồn tại"),
    SESSION_INACTIVE(410, "Session đã bị xóa hoặc không còn hoạt động"),
    SESSION_ACCESS_DENIED(403, "Bạn không có quyền truy cập session này");

    private final int httpStatus;
    private final String message;
}
