package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 쿠폰 수용 자리와 입장 대기 상태의 Redis 구현. 기존 master String template 만 사용하며 키에 TTL 을 두지 않는다.
 *
 * <p>기본 token 자리는 전용 Set 하나다. 대기실(설계 §12.8·12.11)은 캠페인별 키 5개를 Lua 로만 바꾼다.
 * Lua 응답은 문자열 고정 배열이며, 길이·원소 타입·결과명·JSON·숫자·상태별 필수값 중 하나라도 어긋나면 결과로 해석하지 않고
 * IllegalStateException 으로 올린다. null 이나 알 수 없는 응답을 성공·미발견으로 바꾸지 않는다.
 */
@Component
public class CouponAdmissionRedisRepository implements CouponAdmissionRepository {

    public static final String PERMITS_KEY = "commerce:coupon-admission:v1:permits";

    private static final Logger log = LoggerFactory.getLogger(CouponAdmissionRedisRepository.class);

    private static final RedisScript<Long> ACQUIRE_SCRIPT =
        RedisScript.of(new ClassPathResource("redis/coupon-admission-acquire.lua"), Long.class);

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

    /** 대기실 키 5개. 순서는 Lua 공통부의 KEYS 순서(meta, entries, queue, ready, processing)와 같아야 한다. */
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
    public Acquisition tryAcquire(String token, int capacity) {
        Long result = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(PERMITS_KEY), token, String.valueOf(capacity));
        if (result == null) {
            throw new IllegalStateException("수용 자리 획득 응답이 없습니다. [token = " + token + "]");
        }
        if (result == 1L) {
            return Acquisition.ACQUIRED;
        }
        if (result == 0L) {
            return Acquisition.FULL;
        }
        throw new IllegalStateException("수용 자리 획득 응답이 1/0 이 아닙니다. [token = " + token + ", result = " + result + "]");
    }

    @Override
    public long release(String token) {
        Long removed = redisTemplate.opsForSet().remove(PERMITS_KEY, token);
        if (removed == null || removed < 0 || removed > 1) {
            throw new IllegalStateException("수용 자리 반환 응답이 0/1 이 아닙니다. [token = " + token + ", removed = " + removed + "]");
        }
        return removed;
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

    // ---- Lua 응답 변환. 실제 Lua 와 별도로 잘못된 응답 분기를 검사할 수 있게 package-private static 으로 둔다. ----

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

    /** 항목 JSON 과 position 을 snapshot 으로. position 은 WAITING 에만, expiresAt 은 READY 에만 있어야 한다. */
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
        // READY·PROCESSING 은 기한이 필수다. 다른 상태는 과거 기한이 남아 있을 수 있어 있으면 검증만 한다. 응답 expiresAt 은 READY 만 채운다.
        Long expiresAtMillis = null;
        if (status == CouponWaitingState.READY || status == CouponWaitingState.PROCESSING || node.has("expiresAtMillis")) {
            expiresAtMillis = exactPositive(node, "expiresAtMillis", json, operation);
        }
        Instant expiresAt = status == CouponWaitingState.READY ? Instant.ofEpochMilli(expiresAtMillis) : null;
        return new CouponWaitingSnapshot(campaignId, waitingId, status, rank, expiresAt, null);
    }

    /** Lua 가 십진 문자열로 저장한 정수 필드. ZSet score·Lua number 가 정확히 표현하는 1~2^53-1 만 받는다. */
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

    private static void requireId(Long value, String name) {
        if (value == null || value < 1) {
            throw new IllegalArgumentException(name + " 는 1 이상이어야 합니다. [" + name + " = " + value + "]");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 가 비어 있습니다.");
        }
    }

    private static String flag(boolean value) {
        return value ? "1" : "0";
    }

    /** 공통부를 앞에 붙여 한 스크립트로 만든다. Redis 는 합친 본문의 SHA1 로 캐시한다. */
    private static RedisScript<List> waitingScript(String name) {
        return new DefaultRedisScript<>(read("redis/coupon-waiting-common.lua") + "\n" + read("redis/coupon-waiting-" + name + ".lua"),
            List.class);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Lua 스크립트를 읽을 수 없습니다. [path = " + path + "]", e);
        }
    }
}
