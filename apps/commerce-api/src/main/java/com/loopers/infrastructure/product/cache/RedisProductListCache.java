package com.loopers.infrastructure.product.cache;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.loopers.domain.product.ProductListCache;
import com.loopers.domain.product.ProductListCacheException;
import com.loopers.domain.product.ProductListSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Optional;

public class RedisProductListCache implements ProductListCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final ProductListCacheProperties properties;
    private final Counter hits;
    private final Counter misses;
    private final Counter invalid;
    private final Counter getErrors;
    private final Counter puts;
    private final Counter putErrors;

    public RedisProductListCache(StringRedisTemplate redis, ObjectMapper mapper,
                                ProductListCacheProperties properties, MeterRegistry registry) {
        this.redis = redis;
        this.mapper = strictMapper(mapper);
        this.properties = properties;
        hits = counter(registry, "lookup", "hit");
        misses = counter(registry, "lookup", "miss");
        invalid = counter(registry, "lookup", "invalid");
        getErrors = counter(registry, "lookup", "error");
        puts = counter(registry, "store", "success");
        putErrors = counter(registry, "store", "error");
    }

    @Override
    public boolean isEnabled() {
        return properties.enabled();
    }

    @Override
    public Optional<ProductListSnapshot> find(long revision) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String json;
        try {
            json = redis.opsForValue().get(key(revision));
        } catch (DataAccessException exception) {
            getErrors.increment();
            throw new ProductListCacheException("Product list cache read failed", exception);
        }
        if (json == null) {
            misses.increment();
            return Optional.empty();
        }
        try {
            ProductListSnapshot snapshot = mapper.readValue(json, ProductListSnapshot.class);
            if (snapshot == null || snapshot.revision() != revision) {
                invalid.increment();
                return Optional.empty();
            }
            hits.increment();
            return Optional.of(snapshot);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            invalid.increment();
            return Optional.empty();
        }
    }

    @Override
    public void store(ProductListSnapshot snapshot) {
        if (!isEnabled()) {
            return;
        }
        try {
            redis.opsForValue().set(key(snapshot.revision()), mapper.writeValueAsString(snapshot), properties.ttl());
            puts.increment();
        } catch (JsonProcessingException | DataAccessException exception) {
            putErrors.increment();
            throw new ProductListCacheException("Product list cache storage failed", exception);
        }
    }

    private String key(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("Cache revision must be non-negative");
        }
        return properties.namespace() + ":brand=all:sort=likes_desc:page=0:size=20:rev=" + revision;
    }

    private static Counter counter(MeterRegistry registry, String operation, String outcome) {
        return registry.counter("commerce.product.list.cache", "operation", operation, "outcome", outcome);
    }

    private static ObjectMapper strictMapper(ObjectMapper source) {
        ObjectMapper strict = source.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
                DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strict.coercionConfigFor(LogicalType.Textual)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return strict;
    }
}
