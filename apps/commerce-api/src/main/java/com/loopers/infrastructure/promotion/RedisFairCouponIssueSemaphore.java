package com.loopers.infrastructure.promotion;

import com.loopers.application.promotion.CouponIssueLimiterProperties;
import com.loopers.application.promotion.CouponIssueSemaphore;
import com.loopers.config.redis.RedisConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link CouponIssueSemaphore} 의 fair 구현 (DR-62). 도착 순서대로 허가를 준다.
 * 프로모션마다 ZSET 두 개.
 * - holders: member 는 허가 토큰, score 는 lease 만료 시각(µs).
 * - queue: member 는 "serverId:token", score 는 대기 마감 시각(µs) = 도착 시각 + waitTime.
 *   waitTime 이 모두 같으니 score 순서가 곧 도착 순서다. 마감이 지난 대기자는 score 로 한 번에 지운다.
 * 획득은 먼저 시도하지 않고 항상 큐 뒤에 선다. 그다음 빈 자리만큼 큐 앞에서 꺼내 holders 로 옮긴다 (Lua 하나).
 * 반납은 내 토큰을 지우고 같은 방식으로 큐 앞을 채운다.
 * 옮겨진 대기자에게는 그 서버의 채널로 토큰을 PUBLISH 한다. 서버마다 시작할 때 자기 채널 하나만 구독하고,
 * 토큰별 future 로 기다리는 스레드를 깨운다. future 는 Lua 실행 전에 등록하므로 알림을 놓치지 않는다.
 * waitTime 안에 알림이 없으면 로컬에서 끝내고 실패한다. 큐 항목은 마감이 지나 다음 Lua 에서 지워진다.
 * 끝내기 전에 반납을 부른다. 마감 직전에 옮겨졌는데 알림이 유실·지연됐으면 그 허가를 바로 다음 대기자에게 넘긴다.
 * 인터럽트·Redis 오류로 끝날 때도 반납을 불러, 마감 전인 큐 항목과 이미 받은 허가를 함께 지운다.
 * 시각은 Redis TIME 이라 앱 서버 시계와 무관하다. 명령은 master 로, 구독은 master 단독 연결로 보낸다.
 * Redis 오류·인터럽트는 획득 실패로 돌린다 (fail-closed). 반납 오류는 로그만 남긴다. 그 토큰은 lease 가 지나면 회수된다.
 */
@Slf4j
@Component
public class RedisFairCouponIssueSemaphore implements CouponIssueSemaphore, MessageListener {
    static final String WAKE_CHANNEL_PREFIX = "coupon-issue:fair-semaphore:wake:";
    private static final Long ACQUIRED = 1L;

    /**
     * 공통 앞부분. KEYS[1] holders, KEYS[2] queue.
     * ARGV[1] permits, ARGV[2] lease(µs), ARGV[3] 깨우기 채널 prefix, ARGV[4] 내 큐 member (알림을 보내지 않는다).
     * Lua 숫자를 그대로 넘기면 %.14g 로 바뀌어 µs 시각이 잘리므로 score 는 %.0f 문자열로 넘긴다.
     */
    private static final String PRELUDE = """
        local function num(n) return string.format('%.0f', n) end
        local t = redis.call('TIME')
        local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
        local permits = tonumber(ARGV[1])
        local lease = tonumber(ARGV[2])
        local prefix = ARGV[3]
        local me = ARGV[4]
        redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', num(now))
        redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', num(now))
        local function promote()
          local free = permits - redis.call('ZCARD', KEYS[1])
          if free <= 0 then return end
          local waiters = redis.call('ZRANGE', KEYS[2], 0, free - 1)
          if #waiters == 0 then return end
          for _, m in ipairs(waiters) do
            local sep = string.find(m, ':', 1, true)
            local token = string.sub(m, sep + 1)
            redis.call('ZADD', KEYS[1], num(now + lease), token)
            if m ~= me then
              redis.call('PUBLISH', prefix .. string.sub(m, 1, sep - 1), token)
            end
          end
          redis.call('ZREMRANGEBYRANK', KEYS[2], 0, #waiters - 1)
          redis.call('PEXPIRE', KEYS[1], math.ceil(lease / 1000))
        end
        """;

    /** ARGV[5] waitTime(µs), ARGV[6] 내 토큰. 큐 뒤에 서고 앞을 채운다. 나까지 옮겨졌으면 1. */
    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>(PRELUDE + """
        local wait = tonumber(ARGV[5])
        redis.call('ZADD', KEYS[2], num(now + wait), me)
        redis.call('PEXPIRE', KEYS[2], math.ceil(wait / 1000))
        promote()
        if redis.call('ZSCORE', KEYS[1], ARGV[6]) then return 1 end
        return 0
        """, Long.class);

    /** ARGV[5] 내 토큰. 내 큐 항목·허가를 지우고 (없으면 아무것도 안 함) 앞을 채운다. */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(PRELUDE + """
        redis.call('ZREM', KEYS[2], me)
        redis.call('ZREM', KEYS[1], ARGV[5])
        promote()
        return 0
        """, Long.class);

    private final RedisTemplate<String, String> masterTemplate;
    private final String serverId = UUID.randomUUID().toString();
    private final Map<String, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();
    private final String permits;
    private final String leaseMicros;
    private final String waitMicros;
    private final long waitMillis;

    public RedisFairCouponIssueSemaphore(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> masterTemplate,
        RedisMessageListenerContainer listenerContainer,
        CouponIssueLimiterProperties properties
    ) {
        this.masterTemplate = masterTemplate;
        this.permits = String.valueOf(properties.permits());
        this.leaseMicros = String.valueOf(TimeUnit.MILLISECONDS.toMicros(properties.lease().toMillis()));
        this.waitMicros = String.valueOf(TimeUnit.MILLISECONDS.toMicros(properties.waitTime().toMillis()));
        this.waitMillis = properties.waitTime().toMillis();
        listenerContainer.addMessageListener(this, new ChannelTopic(WAKE_CHANNEL_PREFIX + serverId));
    }

    /** coupon-issue:fair-semaphore:{promotionId}:holders, coupon-issue:fair-semaphore:{promotionId}:queue */
    static List<String> keys(Long promotionId) {
        String prefix = "coupon-issue:fair-semaphore:" + promotionId;
        return List.of(prefix + ":holders", prefix + ":queue");
    }

    @Override
    public Optional<String> tryAcquire(Long promotionId) {
        String token = UUID.randomUUID().toString();
        CompletableFuture<Void> wakeUp = new CompletableFuture<>();
        waiters.put(token, wakeUp);
        try {
            Long acquired = masterTemplate.execute(ACQUIRE, keys(promotionId),
                permits, leaseMicros, WAKE_CHANNEL_PREFIX, member(token), waitMicros, token);
            if (ACQUIRED.equals(acquired)) {
                return Optional.of(token);
            }
            wakeUp.get(waitMillis, TimeUnit.MILLISECONDS);
            return Optional.of(token);
        } catch (TimeoutException e) {
            release(promotionId, token);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release(promotionId, token);
            return Optional.empty();
        } catch (ExecutionException e) {
            throw new IllegalStateException("깨우기 future 는 예외로 끝나지 않는다.", e);
        } catch (RuntimeException e) {
            log.warn("쿠폰 발급 허가 획득 실패로 처리 (Redis 오류). [promotionId = {}]", promotionId, e);
            release(promotionId, token);
            return Optional.empty();
        } finally {
            waiters.remove(token);
        }
    }

    /** 내 허가를 반납한다. 아직 큐에 있으면 큐에서도 뺀다. 획득을 포기할 때도 부른다. */
    @Override
    public void release(Long promotionId, String token) {
        try {
            masterTemplate.execute(RELEASE, keys(promotionId), permits, leaseMicros, WAKE_CHANNEL_PREFIX, member(token), token);
        } catch (RuntimeException e) {
            log.warn("쿠폰 발급 허가 반납 실패. lease 가 지나면 회수된다. [promotionId = {}]", promotionId, e);
        }
    }

    /** 이 서버 채널로 온 토큰의 대기 스레드를 깨운다. 이미 포기한 토큰이면 무시한다 (포기할 때 부른 반납이 그 허가를 넘긴다). */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        CompletableFuture<Void> wakeUp = waiters.get(new String(message.getBody(), StandardCharsets.UTF_8));
        if (wakeUp != null) {
            wakeUp.complete(null);
        }
    }

    /** 큐 member. 꺼낸 쪽이 이 서버 채널로 알림을 보낼 수 있게 serverId 를 붙인다. */
    private String member(String token) {
        return serverId + ":" + token;
    }
}
