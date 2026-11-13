package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.loopers.infrastructure.coupon.CouponRedisSupport.readScript;
import static com.loopers.infrastructure.coupon.CouponRedisSupport.requireId;

/**
 * 대기실 Redis 구현. 캠페인별 키 5개를 Lua 로만 바꾸며 키에 TTL 을 두지 않는다.
 * 응답 형식이 어긋나면 결과로 해석하지 않고 예외로 올린다. null·알 수 없는 응답을 성공·미발견으로 바꾸지 않는다.
 */
@Slf4j
@Component
public class CouponAdmissionRedisRepository implements CouponAdmissionRepository {

    private static final RedisScript<List> REGISTER_SCRIPT = waitingScript("register");
    private static final RedisScript<List> FIND_SCRIPT = waitingScript("find");
    private static final RedisScript<List> PROCESS_ADMISSIONS_SCRIPT = waitingScript("process-admissions");
    private static final RedisScript<List> START_SCRIPT = waitingScript("start");
    private static final RedisScript<List> FINISH_SCRIPT = waitingScript("finish");

    private static final String INVARIANT_ERROR = "INVARIANT_ERROR";
    private static final long MAX_EXACT_INTEGER = 9_007_199_254_740_991L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RedisTemplate<String, String> redisTemplate;

    public CouponAdmissionRedisRepository(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate
    ) {
        this.redisTemplate = redisTemplate;
    }

    /** 순서는 Lua 공통부의 KEYS 순서와 같아야 한다. */
    public static List<String> waitingKeys(long campaignId) {
        return List.of(
            "waiting:meta:v1:" + campaignId,
            "waiting:entries:v1:" + campaignId,
            "waiting:queue:v1:" + campaignId,
            "waiting:ready:v1:" + campaignId,
            "waiting:processing:v1:" + campaignId
        );
    }

    @Override
    public Registration register(Long campaignId, Long userId, String newWaitingId) {
        requireId(campaignId, "campaignId");
        requireId(userId, "userId");
        requireText(newWaitingId, "newWaitingId");
        return toRegistration(campaignId, run(REGISTER_SCRIPT, campaignId, userId.toString(), newWaitingId));
    }

    @Override
    public Optional<CouponWaitingSnapshot> find(Long campaignId, Long userId) {
        requireId(campaignId, "campaignId");
        requireId(userId, "userId");
        return toFound(campaignId, run(FIND_SCRIPT, campaignId, userId.toString()));
    }

    @Override
    public AdmissionProgress processAdmissions(Long campaignId, int capacity, long admissionDurationMillis, boolean soldOut, boolean canAdmit) {
        requireId(campaignId, "campaignId");
        if (capacity < 1 || admissionDurationMillis < 1) {
            throw new IllegalArgumentException("capacity·admissionDurationMillis 는 1 이상이어야 합니다. [capacity = " + capacity
                + ", admissionDurationMillis = " + admissionDurationMillis + "]");
        }
        Object reply = run(PROCESS_ADMISSIONS_SCRIPT, campaignId, String.valueOf(capacity), String.valueOf(admissionDurationMillis),
            flag(soldOut), flag(canAdmit));
        return toProgress(reply, capacity);
    }

    @Override
    public Start start(Long campaignId, Long userId, String waitingId) {
        requireId(campaignId, "campaignId");
        requireId(userId, "userId");
        requireText(waitingId, "waitingId");
        return toStart(campaignId, run(START_SCRIPT, campaignId, userId.toString(), waitingId));
    }

    @Override
    public FinishOutcome finish(Long campaignId, Long userId, String waitingId, CouponWaitingState outcome) {
        requireId(campaignId, "campaignId");
        requireId(userId, "userId");
        requireText(waitingId, "waitingId");
        if (outcome != CouponWaitingState.ISSUED && outcome != CouponWaitingState.FAILED) {
            throw new IllegalArgumentException("종료 결과는 ISSUED 또는 FAILED 여야 합니다. [outcome = " + outcome + "]");
        }
        return toFinish(run(FINISH_SCRIPT, campaignId, userId.toString(), waitingId, outcome.name()));
    }

    private Object run(RedisScript<List> script, Long campaignId, String... args) {
        return redisTemplate.execute(script, waitingKeys(campaignId), (Object[]) args);
    }

    // ---- 잘못된 응답 분기를 실제 Lua 없이 검사하려고 package-private static 으로 둔다. ----

    static Registration toRegistration(Long campaignId, Object reply) {
        List<String> parts = parts(reply, 3, "register");
        String result = parts.get(0);
        switch (result) {
            case "CREATED", "EXISTING" -> {
                CouponWaitingSnapshot snapshot = snapshot(campaignId, parts.get(1), parts.get(2), "register");
                Set<CouponWaitingState> allowed = result.equals("CREATED")
                    ? Set.of(CouponWaitingState.WAITING)
                    : Set.of(CouponWaitingState.WAITING, CouponWaitingState.READY, CouponWaitingState.PROCESSING,
                        CouponWaitingState.ISSUED, CouponWaitingState.EXPIRED);
                requireStatus(snapshot, allowed, "register " + result);
                return new Registration(RegistrationOutcome.valueOf(result), snapshot);
            }
            case "SOLD_OUT" -> {
                requireEmpty(parts, 1, "register");
                return new Registration(RegistrationOutcome.SOLD_OUT, null);
            }
            case INVARIANT_ERROR -> {
                logInvariant("register", campaignId, parts.get(1));
                return new Registration(RegistrationOutcome.INVARIANT_ERROR, null);
            }
            default -> throw unexpected("register", reply);
        }
    }

    static Optional<CouponWaitingSnapshot> toFound(Long campaignId, Object reply) {
        List<String> parts = parts(reply, 3, "find");
        return switch (parts.get(0)) {
            case "FOUND" -> Optional.of(snapshot(campaignId, parts.get(1), parts.get(2), "find"));
            case "NOT_FOUND" -> {
                requireEmpty(parts, 1, "find");
                yield Optional.empty();
            }
            case INVARIANT_ERROR -> throw invariantFailure("find", campaignId, parts.get(1));
            default -> throw unexpected("find", reply);
        };
    }

    static AdmissionProgress toProgress(Object reply, int capacity) {
        List<String> parts = parts(reply, 3, "processAdmissions");
        return switch (parts.get(0)) {
            case "OK" -> {
                long expired = nonNegative(parts.get(1), "processAdmissions 회수 수");
                long admitted = nonNegative(parts.get(2), "processAdmissions 승격 수");
                if (expired > capacity || admitted > capacity) {
                    throw unexpected("processAdmissions", reply);
                }
                yield new AdmissionProgress(expired, admitted);
            }
            case INVARIANT_ERROR -> throw invariantFailure("processAdmissions", null, parts.get(1));
            default -> throw unexpected("processAdmissions", reply);
        };
    }

    static Start toStart(Long campaignId, Object reply) {
        List<String> parts = parts(reply, 3, "start");
        String result = parts.get(0);
        switch (result) {
            case "PROCESSING", "ISSUED" -> {
                CouponWaitingSnapshot snapshot = snapshot(campaignId, parts.get(1), parts.get(2), "start");
                requireStatus(snapshot, Set.of(CouponWaitingState.valueOf(result)), "start " + result);
                return new Start(StartOutcome.valueOf(result), snapshot);
            }
            case "STARTED", "NOT_FOUND", "NOT_READY", "EXPIRED", "ALREADY_USED" -> {
                requireEmpty(parts, 1, "start");
                return new Start(StartOutcome.valueOf(result), null);
            }
            case INVARIANT_ERROR -> {
                logInvariant("start", campaignId, parts.get(1));
                return new Start(StartOutcome.INVARIANT_ERROR, null);
            }
            default -> throw unexpected("start", reply);
        }
    }

    static FinishOutcome toFinish(Object reply) {
        List<String> parts = parts(reply, 1, "finish");
        return switch (parts.get(0)) {
            case "APPLIED" -> FinishOutcome.APPLIED;
            case "UNCHANGED" -> FinishOutcome.UNCHANGED;
            case INVARIANT_ERROR -> FinishOutcome.INVARIANT_ERROR;
            default -> throw unexpected("finish", reply);
        };
    }

    private static List<String> parts(Object reply, int size, String operation) {
        if (!(reply instanceof List<?> list) || list.size() != size) {
            throw unexpected(operation, reply);
        }
        for (Object part : list) {
            if (!(part instanceof String)) {
                throw unexpected(operation, reply);
            }
        }
        return list.stream().map(String.class::cast).toList();
    }

    private static CouponWaitingSnapshot snapshot(Long campaignId, String json, String position, String operation) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(operation + " 항목 JSON 을 해석할 수 없습니다. [json = " + json + "]", e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(operation + " 항목 JSON 이 객체가 아닙니다. [json = " + json + "]");
        }
        String waitingId = text(node, "waitingId", json, operation);
        CouponWaitingState status;
        try {
            status = CouponWaitingState.valueOf(text(node, "status", json, operation));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(operation + " 항목 상태를 알 수 없습니다. [json = " + json + "]", e);
        }
        Long rank = null;
        if (status == CouponWaitingState.WAITING) {
            rank = positive(position, operation + " position");
        } else if (!position.isEmpty()) {
            throw new IllegalStateException(operation + " position 은 WAITING 에만 있어야 합니다. [json = " + json + ", position = " + position + "]");
        }
        exactPositive(node, "sequence", json, operation);
        // READY·PROCESSING 은 기한이 필수다. 다른 상태에 남은 기한은 검증만 한다.
        Long expiresAtMillis = null;
        if (status == CouponWaitingState.READY || status == CouponWaitingState.PROCESSING || node.has("expiresAtMillis")) {
            expiresAtMillis = exactPositive(node, "expiresAtMillis", json, operation);
        }
        Instant expiresAt = status == CouponWaitingState.READY ? Instant.ofEpochMilli(expiresAtMillis) : null;
        return new CouponWaitingSnapshot(campaignId, waitingId, status, rank, expiresAt, null);
    }

    /** ZSet score·Lua number 가 정확히 표현하는 1~2^53-1 만 받는다. */
    private static long exactPositive(JsonNode node, String field, String json, String operation) {
        long value = positive(text(node, field, json, operation), operation + " " + field);
        if (value > MAX_EXACT_INTEGER) {
            throw new IllegalStateException(operation + " " + field + " 가 정확 정수 범위(2^53-1)를 넘습니다. [json = " + json + "]");
        }
        return value;
    }

    private static String text(JsonNode node, String field, String json, String operation) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalStateException(operation + " 항목의 " + field + " 가 문자열이 아닙니다. [json = " + json + "]");
        }
        return value.asText();
    }

    private static long positive(String value, String what) {
        long parsed = nonNegative(value, what);
        if (parsed < 1) {
            throw new IllegalStateException(what + " 가 1 이상이 아닙니다. [value = " + value + "]");
        }
        return parsed;
    }

    private static long nonNegative(String value, String what) {
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new IllegalStateException(what + " 가 십진 정수가 아닙니다. [value = " + value + "]");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(what + " 가 long 범위를 넘습니다. [value = " + value + "]", e);
        }
    }

    private static void requireStatus(CouponWaitingSnapshot snapshot, Set<CouponWaitingState> allowed, String what) {
        if (!allowed.contains(snapshot.status())) {
            throw new IllegalStateException(what + " 의 상태가 허용 범위가 아닙니다. [status = " + snapshot.status() + "]");
        }
    }

    private static void requireEmpty(List<String> parts, int from, String operation) {
        for (int i = from; i < parts.size(); i++) {
            if (!parts.get(i).isEmpty()) {
                throw unexpected(operation, parts);
            }
        }
    }

    private static void logInvariant(String operation, Long campaignId, String reason) {
        log.warn("쿠폰 대기 상태를 설명할 수 없어 {} 가 쓰지 않았습니다. [campaignId = {}, reason = {}]", operation, campaignId, reason);
    }

    private static IllegalStateException invariantFailure(String operation, Long campaignId, String reason) {
        return new IllegalStateException(operation + " INVARIANT_ERROR: 쿠폰 대기 상태를 설명할 수 없습니다. [campaignId = "
            + campaignId + ", reason = " + reason + "]");
    }

    private static IllegalStateException unexpected(String operation, Object reply) {
        return new IllegalStateException(operation + " Lua 응답 형식이 계약과 다릅니다. [reply = " + reply + "]");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 가 비어 있습니다.");
        }
    }

    private static String flag(boolean value) {
        return value ? "1" : "0";
    }

    private static RedisScript<List> waitingScript(String name) {
        return new DefaultRedisScript<>(readScript("redis/coupon-waiting-common.lua") + "\n" + readScript("redis/coupon-waiting-" + name + ".lua"),
            List.class);
    }
}
