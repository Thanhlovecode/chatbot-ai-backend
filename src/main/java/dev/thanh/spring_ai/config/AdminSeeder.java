package dev.thanh.spring_ai.config;

import dev.thanh.spring_ai.entity.User;
import dev.thanh.spring_ai.enums.UserRole;
import dev.thanh.spring_ai.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds admin users on application startup based on configured emails.
 * <p>
 * Behavior:
 * <ul>
 *   <li>Email not in DB → CREATE new user with role=ADMIN</li>
 *   <li>Email exists with role=USER → PROMOTE to ADMIN</li>
 *   <li>Email already ADMIN → SKIP</li>
 *   <li>No ADMIN_EMAILS configured → SKIP entirely</li>
 * </ul>
 * <p>
 * Since the app uses Google OAuth (no password required), creating a user
 * with just email + role is sufficient. When the admin later logs in via
 * Google, {@code AuthenticationService.processExistingUser()} will
 * automatically link their googleId and avatar.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements ApplicationRunner {

    private final AdminProperties adminProperties;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<String> emails = adminProperties.getEmails();

        if (emails == null || emails.isEmpty()) {
            log.info("No ADMIN_EMAILS configured — skipping admin seeding");
            return;
        }

        log.info("Admin seeding started — processing {} email(s)", emails.size());

        for (String rawEmail : emails) {
            String email = rawEmail.trim().toLowerCase();
            if (email.isBlank()) {
                continue;
            }
            seedAdmin(email);
        }

        log.info("Admin seeding completed");
    }

    private void seedAdmin(String email) {
        userRepository.findByEmail(email)
                .ifPresentOrElse(
                        this::promoteIfNeeded,
                        () -> createAdminUser(email)
                );
    }

    private void promoteIfNeeded(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            log.info("✅ Already admin: {}", user.getEmail());
            return;
        }

        user.setRole(UserRole.ADMIN);
        userRepository.save(user);
        log.info("✅ Admin promoted: {} (USER → ADMIN)", user.getEmail());
    }

    private void createAdminUser(String email) {
        User admin = User.builder()
                .email(email)
                .displayName("Admin")
                .role(UserRole.ADMIN)
                .active(true)
                .build();

        userRepository.save(admin);
        log.info("✅ Admin seeded: {} (created new user)", email);
    }
}
