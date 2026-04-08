package dev.thanh.spring_ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

import static com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class RedisConfig {

    private final RedisStreamProperties redisStreamProperties;
    private final ObjectMapper objectMapper;


    /**
     * Customize Lettuce client options while letting Spring Boot auto-configure
     * LettuceConnectionFactory from application*.yaml (including pool settings).
     *
     * Tại sao dùng Customizer thay vì @Bean LettuceConnectionFactory?
     * → Khi tự tạo @Bean LettuceConnectionFactory, Spring Boot AUTO-CONFIG bị SKIP hoàn toàn.
     *   Tức là tất cả config trong YAML (host, port, password, pool) đều bị ignore.
     * → Dùng Customizer: Spring Boot VẪN tự tạo factory từ YAML, chỉ thêm custom options.
     *   → Pool config (max-active, max-idle, ...) trong YAML được áp dụng đúng.
     */
    @Bean
    public LettuceClientConfigurationBuilderCustomizer lettuceCustomizer() {
        return builder -> {
            SocketOptions socketOptions = SocketOptions.builder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .keepAlive(true)
                    .tcpNoDelay(true)
                    .build();

            ClientOptions clientOptions = ClientOptions.builder()
                    .socketOptions(socketOptions)
                    .autoReconnect(true)
                    .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                    .build();

            builder.clientOptions(clientOptions)
                    .commandTimeout(Duration.ofSeconds(30))
                    .shutdownTimeout(Duration.ofSeconds(2));

            log.info("✅ Lettuce client customized: keepAlive, tcpNoDelay, autoReconnect, REJECT on disconnect");
        };
    }


    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);

        ObjectMapper redisObjectMapper = createRedisObjectMapper();

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper);

        // Serializers
        redisTemplate.setKeySerializer(stringSerializer);
        redisTemplate.setValueSerializer(jsonSerializer);
        redisTemplate.setHashKeySerializer(stringSerializer);
        redisTemplate.setHashValueSerializer(jsonSerializer);

        redisTemplate.afterPropertiesSet();

        log.info("✅ RedisTemplate configured with pool-backed connection factory");

        return redisTemplate;
    }


    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper cacheObjectMapper = createRedisObjectMapper();
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(cacheObjectMapper);

        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(30))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer));

        log.info("✅ RedisCacheManager configured with JSON serializer");

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfig)
                .build();
    }


    /**
     * Initialize Redis Stream consumer group on startup.
     * Uses CommandLineRunner instead of returning a boolean bean (anti-pattern).
     */
    @Bean
    public CommandLineRunner initConsumerGroup(RedisTemplate<String, Object> redisTemplate) {
        return args -> {
            try {
                redisTemplate.opsForStream().createGroup(
                        redisStreamProperties.getName(),
                        redisStreamProperties.getConsumerGroup()
                );
                log.info("✅ Created consumer group '{}' for stream '{}'",
                        redisStreamProperties.getConsumerGroup(),
                        redisStreamProperties.getName());
            } catch (org.springframework.data.redis.RedisSystemException e) {
                // BUSYGROUP — consumer group already exists, which is expected
                log.debug("Consumer group '{}' already exists for stream '{}'",
                        redisStreamProperties.getConsumerGroup(),
                        redisStreamProperties.getName());
            }
        };
    }

    /**
     * Create a copy of the application ObjectMapper with default typing enabled for Redis serialization.
     * The base ObjectMapper already has JavaTimeModule and WRITE_DATES_AS_TIMESTAMPS disabled,
     * so we only need to add polymorphic type info.
     */
    private ObjectMapper createRedisObjectMapper() {
        ObjectMapper redisObjectMapper = objectMapper.copy();
        redisObjectMapper.activateDefaultTyping(
                redisObjectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL,
                PROPERTY);
        return redisObjectMapper;
    }
}
