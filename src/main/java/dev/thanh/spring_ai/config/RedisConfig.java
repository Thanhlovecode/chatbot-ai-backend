package dev.thanh.spring_ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thanh.spring_ai.dto.request.MessageDTO;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
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
         * <p>
         * <b>Tại sao dùng Customizer thay vì @Bean LettuceConnectionFactory?</b>
         * <ul>
         * <li>Khi tự tạo @Bean LettuceConnectionFactory → Spring Boot AUTO-CONFIG bị
         * SKIP hoàn toàn,
         * tức là tất cả config trong YAML (host, port, password, pool) đều bị
         * ignore.</li>
         * <li>Dùng Customizer → Spring Boot VẪN tự tạo factory từ YAML, chỉ thêm custom
         * options.</li>
         * </ul>
         *
         * <p>
         * <b>⚠️ SINGLE SOURCE OF TRUTH cho Timeout:</b>
         * <br>
         * Customizer chạy SAU khi nạp YAML → sẽ ghi đè mọi giá trị timeout trong YAML.
         * <br>
         * Do đó, tất cả timeout được quản lý tập trung TẠI ĐÂY. Không khai báo lại
         * trong YAML.
         * <ul>
         * <li>connectTimeout = 3s — TCP socket connect (Redis thường < 1ms, 3s đủ
         * buffer cho network jitter)</li>
         * <li>commandTimeout = 3s — thời gian chờ response từ Redis (fail-fast →
         * Circuit Breaker xử lý)</li>
         * <li>shutdownTimeout = 2s — thời gian chờ drain khi graceful shutdown</li>
         * </ul>
         */
        @Bean
        public LettuceClientConfigurationBuilderCustomizer lettuceCustomizer() {
                return builder -> {
                        SocketOptions socketOptions = SocketOptions.builder()
                                        .connectTimeout(Duration.ofSeconds(3)) // 3s — đủ buffer, fail-fast nếu Redis
                                                                               // unreachable
                                        .keepAlive(true)
                                        .tcpNoDelay(true)
                                        .build();

                        ClientOptions clientOptions = ClientOptions.builder()
                                        .socketOptions(socketOptions)
                                        .autoReconnect(true)
                                        .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                                        .build();

                        builder.clientOptions(clientOptions)
                                        .commandTimeout(Duration.ofSeconds(3)) // 3s — chatbot chỉ GET/SET/XADD, > 5s là
                                                                               // bất thường
                                        .shutdownTimeout(Duration.ofSeconds(2)); // Graceful shutdown drain

                        log.info("✅ Lettuce client customized: connectTimeout=3s, commandTimeout=3s, shutdownTimeout=2s, keepAlive, tcpNoDelay, autoReconnect, REJECT on disconnect");
                };
        }

        @Bean
        public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
                RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
                redisTemplate.setConnectionFactory(connectionFactory);

                ObjectMapper redisObjectMapper = createRedisObjectMapper();

                StringRedisSerializer stringSerializer = new StringRedisSerializer();
                GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(
                                redisObjectMapper);

                // Serializers
                redisTemplate.setKeySerializer(stringSerializer);
                redisTemplate.setValueSerializer(jsonSerializer);
                redisTemplate.setHashKeySerializer(stringSerializer);
                redisTemplate.setHashValueSerializer(jsonSerializer);

                redisTemplate.afterPropertiesSet();

                log.info("✅ RedisTemplate configured with pool-backed connection factory");

                return redisTemplate;
        }

        /**
         * Dedicated RedisTemplate for chat history cache (chat:history:{sessionId}).
         * <p>
         * <b>Tại sao tách riêng?</b>
         * <ul>
         * <li>History cache CHỈ lưu {@link MessageDTO} — type biết tại compile
         * time</li>
         * <li>Dùng {@link Jackson2JsonRedisSerializer} typed → KHÔNG cần @class
         * metadata</li>
         * <li>Không cần {@code DefaultTyping.NON_FINAL} → KHÔNG reflection khi
         * deserialize</li>
         * <li>JSON nhỏ hơn ~50% → ít bandwidth Redis, ít CPU parse</li>
         * </ul>
         * <p>
         * <b>So sánh CPU per MessageDTO:</b>
         * <ul>
         * <li>GenericJackson2Json + NON_FINAL: ~100-140μs (reflection + type
         * resolve)</li>
         * <li>Jackson2Json typed: ~20-30μs (direct field mapping)</li>
         * </ul>
         */
        @Bean
        public RedisTemplate<String, MessageDTO> historyRedisTemplate(RedisConnectionFactory connectionFactory) {
                RedisTemplate<String, MessageDTO> template = new RedisTemplate<>();
                template.setConnectionFactory(connectionFactory);

                StringRedisSerializer stringSerializer = new StringRedisSerializer();
                // Typed serializer: Jackson biết type tại compile time → zero reflection
                Jackson2JsonRedisSerializer<MessageDTO> typedSerializer = new Jackson2JsonRedisSerializer<>(
                                objectMapper, MessageDTO.class);

                template.setKeySerializer(stringSerializer);
                template.setValueSerializer(typedSerializer);
                template.setHashKeySerializer(stringSerializer);
                template.setHashValueSerializer(typedSerializer);

                template.afterPropertiesSet();

                log.info("✅ historyRedisTemplate configured with typed Jackson2JsonRedisSerializer<MessageDTO>");

                return template;
        }

        @Bean
        public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
                ObjectMapper cacheObjectMapper = createRedisObjectMapper();
                GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(
                                cacheObjectMapper);

                RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
                                .entryTtl(Duration.ofMinutes(30))
                                .disableCachingNullValues()
                                .serializeKeysWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(new StringRedisSerializer()))
                                .serializeValuesWith(RedisSerializationContext.SerializationPair
                                                .fromSerializer(jsonSerializer));

                log.info("✅ RedisCacheManager configured with JSON serializer");

                return RedisCacheManager.builder(connectionFactory)
                                .cacheDefaults(cacheConfig)
                                .build();
        }

        /**
         * Create a copy of the application ObjectMapper with default typing enabled for
         * Redis serialization.
         * The base ObjectMapper already has JavaTimeModule and
         * WRITE_DATES_AS_TIMESTAMPS disabled,
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
