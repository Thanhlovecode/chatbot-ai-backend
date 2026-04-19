package dev.thanh.spring_ai.exception;

/**
 * Ném khi hệ thống đang ở trạng thái degraded (Redis CB OPEN hoặc DB Bulkhead full).
 * <p>
 * Non-critical operations (xem danh sách session, lấy timestamps...) sẽ bị reject
 * với HTTP 503 Service Unavailable thay vì fallback xuống DB — nhằm bảo vệ
 * DB connection pool không bị spike khi Redis failure.
 * <p>
 * Critical operations (chat pipeline) vẫn được phép fallback qua DB Bulkhead.
 */
public class ServiceDegradedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ServiceDegradedException(String message) {
        super(message);
    }

    public ServiceDegradedException(String message, Throwable cause) {
        super(message, cause);
    }
}
