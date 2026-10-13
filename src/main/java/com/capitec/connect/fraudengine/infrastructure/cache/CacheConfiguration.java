package com.capitec.connect.fraudengine.infrastructure.cache;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis Cache Configuration for Fraud Engine.
 * Configures the cache manager backing historical account-transaction lookups
 * to ensure sub-50ms response times.
 */
@EnableCaching
@Configuration
public class CacheConfiguration {

    public static final String ACCOUNT_HISTORY_CACHE = "account-history";

    /**
     * Value serializer used for cache entries.
     * <p>
     * {@code GenericJackson2JsonRedisSerializer} keeps the default {@code @class} type header (so
     * {@code List<Transaction>} round-trips as a typed graph), but its plain {@code ObjectMapper} has no
     * Java 8 date/time support. Registering {@link JavaTimeModule} is required, otherwise serializing
     * {@code Transaction#timestamp} (a {@link java.time.Instant}) throws
     * "Java 8 date/time type not supported by default", which fails the cache write and propagates out of
     * the caching interceptor as a rule-evaluation failure.
     * <p>
     * Dates are written as ISO-8601 strings instead of epoch timestamps because the default
     * floating-point representation cannot carry nanosecond precision.
     *
     * @return JSON serializer with java.time support
     */
    @Bean
    public GenericJackson2JsonRedisSerializer cacheValueSerializer() {
        return new GenericJackson2JsonRedisSerializer()
            .configure(objectMapper -> objectMapper
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    /**
     * Default cache configuration with 10-minute TTL.
     * Aligned with the 5-minute time buckets used in cache keys (2 bucket retention).
     */
    @Bean
    public RedisCacheConfiguration defaultCacheConfig(GenericJackson2JsonRedisSerializer cacheValueSerializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .disableCachingNullValues()
            .serializeKeysWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(cacheValueSerializer));
    }

    /**
     * Redis Cache Manager with cache configurations optimized for fraud detection.
     */
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          RedisCacheConfiguration defaultCacheConfig) {
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultCacheConfig)
            .build();
    }
}