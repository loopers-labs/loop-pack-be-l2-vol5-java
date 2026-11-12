package com.loopers.coupon.adapter.out.redis;

import com.loopers.config.redis.RedisConfig;
import com.loopers.coupon.application.port.out.CouponGatePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 판정 상태를 primary에서만 읽고 쓴다 (11-11 "Redis 읽기 위치": replica는 늦을 수 있다 → redisTemplateMaster).
 * 키는 {@code coupon:{campaignId}:...} — 중괄호는 Redis Cluster의 hash tag라 한 캠페인의 키가 같은 slot에 모인다(지금은 단일 primary).
 * 사용자별 키(입장 키)와 구간 카운터는 Lua 안에서 앞부분 + 값으로 만든다.
 */
@Component
public class CouponGateRedisAdapter implements CouponGatePort {

    private final RedisTemplate<String, String> redis;
    private final int admitPerTick;
    private final long tickMillis;
    private final long entryTtlSeconds;

    private final DefaultRedisScript<String> issueScript = script("issue.lua", String.class);
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> registerScript = script("register.lua", List.class);
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> statusScript = script("status.lua", List.class);
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> admitScript = script("admit.lua", List.class);
    private final DefaultRedisScript<Long> rebuildScript = script("rebuild.lua", Long.class);

    public CouponGateRedisAdapter(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
        @Value("${coupon.queue.admission.per-tick}") int admitPerTick,
        @Value("${coupon.queue.admission.tick}") Duration tick,
        @Value("${coupon.queue.entry-ttl}") Duration entryTtl
    ) {
        this.redis = redis;
        this.admitPerTick = admitPerTick;
        this.tickMillis = tick.toMillis();
        this.entryTtlSeconds = entryTtl.toSeconds();
    }

    static String prefix(Long campaignId) {
        return "coupon:{" + campaignId + "}:";
    }

    static String stateKey(Long campaignId) {
        return prefix(campaignId) + "state";
    }

    static String winnersKey(Long campaignId) {
        return prefix(campaignId) + "winners";
    }

    static String queueKey(Long campaignId) {
        return prefix(campaignId) + "queue";
    }

    static String seqKey(Long campaignId) {
        return prefix(campaignId) + "queue:seq";
    }

    static String entryPrefix(Long campaignId) {
        return prefix(campaignId) + "entry:";
    }

    static String admitPrefix(Long campaignId) {
        return prefix(campaignId) + "admit:";
    }

    static String rebuildLockKey(Long campaignId) {
        return prefix(campaignId) + "rebuild";
    }

    @Override
    public IssueDecision issue(Long campaignId, Long userId, int totalQuantity) {
        String result = call(() -> redis.execute(issueScript,
            List.of(stateKey(campaignId), winnersKey(campaignId), entryPrefix(campaignId) + userId),
            String.valueOf(userId), String.valueOf(totalQuantity)));
        return IssueDecision.valueOf(result);
    }

    @Override
    public QueueState register(Long campaignId, Long userId, int totalQuantity, int queueCapacity) {
        List<?> result = call(() -> redis.execute(registerScript,
            List.of(stateKey(campaignId), winnersKey(campaignId), queueKey(campaignId), seqKey(campaignId), entryPrefix(campaignId) + userId),
            String.valueOf(userId), String.valueOf(totalQuantity), String.valueOf(queueCapacity), String.valueOf(admitPerTick),
            String.valueOf(tickMillis), String.valueOf(entryTtlSeconds), admitPrefix(campaignId)));
        return toQueueState(result);
    }

    @Override
    public QueueState status(Long campaignId, Long userId, int totalQuantity) {
        List<?> result = call(() -> redis.execute(statusScript,
            List.of(stateKey(campaignId), winnersKey(campaignId), queueKey(campaignId), entryPrefix(campaignId) + userId),
            String.valueOf(userId), String.valueOf(totalQuantity)));
        return toQueueState(result);
    }

    @Override
    public AdmitResult admit(Long campaignId, int totalQuantity) {
        List<?> result = call(() -> redis.execute(admitScript,
            List.of(stateKey(campaignId), winnersKey(campaignId), queueKey(campaignId)),
            String.valueOf(totalQuantity), String.valueOf(admitPerTick), String.valueOf(tickMillis), String.valueOf(entryTtlSeconds),
            admitPrefix(campaignId), entryPrefix(campaignId)));
        return new AdmitResult(AdmitStatus.valueOf(String.valueOf(result.get(0))), ((Number) result.get(1)).intValue());
    }

    @Override
    public boolean tryLockRebuild(Long campaignId) {
        return Boolean.TRUE.equals(call(() -> redis.opsForValue().setIfAbsent(rebuildLockKey(campaignId), "1", Duration.ofSeconds(10))));
    }

    @Override
    public void rebuild(Long campaignId, Set<Long> winners) {
        Object[] args = winners.stream().map(String::valueOf).toArray();
        call(() -> redis.execute(rebuildScript, List.of(stateKey(campaignId), winnersKey(campaignId)), args));
    }

    @Override
    public Set<Long> winners(Long campaignId) {
        Set<String> members = call(() -> redis.opsForSet().members(winnersKey(campaignId)));
        Set<Long> ids = new HashSet<>();
        if (members != null) {
            members.forEach(member -> ids.add(Long.valueOf(member)));
        }
        return ids;
    }

    @Override
    public void deleteEntry(Long campaignId, Long userId) {
        call(() -> redis.delete(entryPrefix(campaignId) + userId));
    }

    @Override
    public void cleanup(Long campaignId) {
        List<String> keys = new ArrayList<>(List.of(queueKey(campaignId), seqKey(campaignId), winnersKey(campaignId), stateKey(campaignId)));
        call(() -> redis.unlink(keys));
    }

    private static QueueState toQueueState(List<?> result) {
        return new QueueState(QueueStateType.valueOf(String.valueOf(result.get(0))), ((Number) result.get(1)).longValue());
    }

    private static <T> T call(Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException e) {
            throw new GateUnavailableException("Redis 판정 상태에 닿지 못했습니다", e);
        }
    }

    private static <T> DefaultRedisScript<T> script(String file, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/coupon/" + file));
        script.setResultType(type);
        return script;
    }
}
