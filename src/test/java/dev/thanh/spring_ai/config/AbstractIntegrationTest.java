package dev.thanh.spring_ai.config;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Base class cho tất cả các Integration Tests hoặc DataJpaTests.
 * Sử dụng pattern Singleton Testcontainers để khởi động Postgres & Redis
 * MỘT LẦN DUY NHẤT cho toàn bộ chu trình chạy Maven Test, giảm 60-70% overhead time.
 */
@SpringBootTest
@ActiveProfiles("integration")
public abstract class AbstractIntegrationTest {

    @MockitoBean(name = "documentVectorStore")
    protected VectorStore documentVectorStore;

    @MockitoBean(name = "queryVectorStore")
    protected VectorStore queryVectorStore;

    @MockitoBean(name = "documentEmbeddingModel")
    protected EmbeddingModel documentEmbeddingModel;

    @MockitoBean(name = "queryEmbedding")
    protected EmbeddingModel queryEmbeddingModel;

    // 1. PostgreSQL Container (Sử dụng ServiceConnection của Spring Boot 3.1+)
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // 2. Redis Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    static {
        // Start containers manual statically để chúng sống suốt vòng đời JVM test
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        // Redis properties override
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        
        // Cố định Datasource properties phòng hờ (Mặc dù @ServiceConnection đã lo 99%)
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
