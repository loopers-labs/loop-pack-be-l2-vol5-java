package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCacheRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * 쿠폰 기발급 결과·품절 캐시의 Redis 구현(설계 §13.9). 기존 master String template 만 쓴다.
 * 저장은 SET NX PX, 읽기는 GET+PTTL 을 원자적으로 하는 읽기 Lua 하나를 두 캐시가 공유한다.
 *
 * <p>기발급 JSON 은 전용 mapper 의 트리로만 읽는다. 숫자 문자열·실수·범위 밖 정수를 정수로 바꾸지 않고, 다섯 필드 외 값·누락·null·
 * 요청 campaignId 불일치는 손상으로 본다. 손상·설명할 수 없는 응답은 IllegalStateException 등 예외로 올리고 키를 지우거나 고치지 않는다.
 */
@Component
public class CouponCacheRedisRepository implements CouponCacheRepository {

    private static final RedisScript<List> READ_SCRIPT = new DefaultRedisScript<>(read("redis/coupon-cache-read.lua"), List.class);
    private static final Set<String> ISSUED_FIELDS = Set.of("couponId", "campaignId", "campaignName", "benefitType", "benefitValue");
    private static final String SOLD_OUT_VALUE = "true";
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final RedisTemplate<String, String> redisTemplate;

    public CouponCacheRedisRepository(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    /** 키는 campaign:user 순서다. 포트 인자(user, campaign)와 순서가 다르다. */
    public static String issuedKey(long userId, long campaignId) {
        return "coupon:issued:v1:" + campaignId + ":" + userId;
    }

    public static String soldOutKey(long campaignId) {
        return "coupon:sold-out:v1:" + campaignId;
    }

    @Override
    public Optional<IssuedCoupon> findIssued(Long userId, Long campaignId) {
        requireId(userId, "userId");
        requireId(campaignId, "campaignId");
        return readHit(redisTemplate.execute(READ_SCRIPT, List.of(issuedKey(userId, campaignId))))
            .map(hit -> toIssued(hit.value(), campaignId));
    }

    @Override
    public void putIssuedIfAbsent(Long userId, Long campaignId, IssuedCoupon value, long ttlMillis) {
        requireId(userId, "userId");
        requireId(campaignId, "campaignId");
        requireTtl(ttlMillis);
        if (value == null || !value.campaignId().equals(campaignId)) {
            throw new IllegalArgumentException("저장할 발급 결과의 campaignId 가 요청과 다릅니다. [campaignId = " + campaignId + "]");
        }
        setIfAbsent(issuedKey(userId, campaignId), toJson(value), ttlMillis);
    }

    @Override
    public OptionalLong findSoldOutTtl(Long campaignId) {
        requireId(campaignId, "campaignId");
        Optional<Hit> hit = readHit(redisTemplate.execute(READ_SCRIPT, List.of(soldOutKey(campaignId))));
        if (hit.isEmpty()) {
            return OptionalLong.empty();
        }
        if (!SOLD_OUT_VALUE.equals(hit.get().value())) {
            throw new IllegalStateException("품절 캐시 값이 true 가 아닙니다. [campaignId = " + campaignId + ", value = " + hit.get().value() + "]");
        }
        return OptionalLong.of(hit.get().remainingMillis());
    }

    @Override
    public void putSoldOutIfAbsent(Long campaignId, long ttlMillis) {
        requireId(campaignId, "campaignId");
        requireTtl(ttlMillis);
        setIfAbsent(soldOutKey(campaignId), SOLD_OUT_VALUE, ttlMillis);
    }

    /** SET NX PX. 이미 있으면 false 이며 값·TTL 은 그대로다. null 은 결과를 알 수 없는 응답이다. */
    private void setIfAbsent(String key, String value, long ttlMillis) {
        Boolean applied = redisTemplate.opsForValue().setIfAbsent(key, value, Duration.ofMillis(ttlMillis));
        if (applied == null) {
            throw new IllegalStateException("캐시 저장 결과를 알 수 없습니다. [key = " + key + "]");
        }
    }

    // ---- 응답·값 변환. 실제 Redis 로 만들 수 없는 응답도 검사할 수 있게 package-private static 으로 둔다. ----

    record Hit(String value, long remainingMillis) {
    }

    /** [MISS, "", ""] 또는 [HIT, 비어 있지 않은 값, 양의 십진 정수]만 받는다. */
    static Optional<Hit> readHit(Object reply) {
        if (!(reply instanceof List<?> list) || list.size() != 3 || !list.stream().allMatch(String.class::isInstance)) {
            throw unexpected(reply);
        }
        String result = (String) list.get(0);
        String value = (String) list.get(1);
        String remaining = (String) list.get(2);
        if (result.equals("MISS") && value.isEmpty() && remaining.isEmpty()) {
            return Optional.empty();
        }
        if (!result.equals("HIT") || value.isEmpty() || !remaining.matches("[1-9][0-9]*")) {
            throw unexpected(reply);
        }
        try {
            return Optional.of(new Hit(value, Long.parseLong(remaining)));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("캐시 남은 수명이 long 범위를 넘습니다. [reply = " + reply + "]", e);
        }
    }

    static IssuedCoupon toIssued(String json, Long campaignId) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("기발급 캐시 JSON 을 해석할 수 없습니다. [campaignId = " + campaignId + "]", e);
        }
        if (node == null || !node.isObject() || !fieldNames(node).equals(ISSUED_FIELDS)) {
            throw new IllegalStateException("기발급 캐시 JSON 이 다섯 필드 객체가 아닙니다. [campaignId = " + campaignId + ", json = " + json + "]");
        }
        long cachedCampaign = longField(node, "campaignId", json);
        if (cachedCampaign != campaignId) {
            throw new IllegalStateException("기발급 캐시의 campaignId 가 요청과 다릅니다. [campaignId = " + campaignId + ", json = " + json + "]");
        }
        CouponBenefitType type;
        try {
            type = CouponBenefitType.valueOf(textField(node, "benefitType", json));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("기발급 캐시의 혜택 유형을 알 수 없습니다. [json = " + json + "]", e);
        }
        return new IssuedCoupon(longField(node, "couponId", json), cachedCampaign, textField(node, "campaignName", json),
            type, longField(node, "benefitValue", json));
    }

    static String toJson(IssuedCoupon value) {
        ObjectNode node = JSON.createObjectNode()
            .put("couponId", value.couponId())
            .put("campaignId", value.campaignId())
            .put("campaignName", value.campaignName())
            .put("benefitType", value.benefitType().name())
            .put("benefitValue", value.benefitValue());
        return node.toString();
    }

    private static Set<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        return Set.copyOf(names);
    }

    /** JSON 정수 리터럴이면서 long 범위인 값만. "1"·1.0·1e0·범위 밖은 거절한다. */
    private static long longField(JsonNode node, String field, String json) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalStateException("기발급 캐시의 " + field + " 가 long 정수가 아닙니다. [json = " + json + "]");
        }
        return value.longValue();
    }

    private static String textField(JsonNode node, String field, String json) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("기발급 캐시의 " + field + " 가 문자열이 아닙니다. [json = " + json + "]");
        }
        return value.textValue();
    }

    private static IllegalStateException unexpected(Object reply) {
        return new IllegalStateException("캐시 읽기 Lua 응답 형식이 계약과 다릅니다. [reply = " + reply + "]");
    }

    private static void requireId(Long value, String name) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(name + " 는 1 이상이어야 합니다. [" + name + " = " + value + "]");
        }
    }

    private static void requireTtl(long ttlMillis) {
        if (ttlMillis < 1) {
            throw new IllegalArgumentException("캐시 TTL 은 1ms 이상이어야 합니다. [ttlMillis = " + ttlMillis + "]");
        }
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Lua 스크립트를 읽을 수 없습니다. [path = " + path + "]", e);
        }
    }
}
