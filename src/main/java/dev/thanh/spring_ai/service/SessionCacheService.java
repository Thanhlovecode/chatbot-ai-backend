package dev.thanh.spring_ai.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Cache chỉ lưu sessionId (String) — tối ưu bộ nhớ Redis.
 * Key và Value đều là sessionId: dùng cache như một "Set" để đánh dấu session hợp lệ.
 *
 * unless = "#result == null" đảm bảo không cache giá trị null (khi session không tồn tại).
 */
@Service
public class SessionCacheService {

    private static final String CACHE_NAME = "sessions";

    /**
     * Ghi sessionId vào cache. Được gọi sau AFTER_COMMIT bởi SessionEventListener
     * hoặc khi warm-up cache sau DB lookup.
     */
    @CachePut(value = CACHE_NAME, key = "#sessionId")
    public String cacheSessionId(String sessionId) {
        return sessionId;
    }

    /**
     * Trả về sessionId nếu đã có trong cache, null nếu miss.
     * Dùng kết quả trả về để kiểm tra: result != null → cache hit.
     *
     * Lưu ý: phương thức này KHÔNG tự cache null vào Redis (unless = "#result == null"),
     * nên cache miss sẽ không tạo entry rác.
     */
    @Cacheable(value = CACHE_NAME, key = "#sessionId", unless = "#result == null")
    public String getIfCached(String sessionId) {
        return null; // Cache miss: trả về null để caller biết phải xuống DB
    }

    /**
     * Xóa sessionId khỏi cache (dùng khi soft-delete session).
     */
    @CacheEvict(value = CACHE_NAME, key = "#sessionId")
    public void evict(String sessionId) {
        // Spring tự xóa cache entry theo key
    }
}
