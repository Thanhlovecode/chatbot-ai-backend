package dev.thanh.spring_ai.event;

import dev.thanh.spring_ai.entity.Session;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Event được publish sau khi một Session mới được lưu thành công vào DB.
 * Listener sẽ dùng @TransactionalEventListener(phase = AFTER_COMMIT)
 * để đảm bảo chỉ write vào cache khi transaction đã commit hoàn toàn.
 */
@Getter
public class SessionCreatedEvent extends ApplicationEvent {

    private final Session session;

    public SessionCreatedEvent(Object source, Session session) {
        super(source);
        this.session = session;
    }
}
