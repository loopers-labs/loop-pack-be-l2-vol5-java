package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponQueuePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class RedisQueueAdapter implements CouponQueuePort {

    private final RedisTemplate<String, String> redisTemplate;

    private final RedisScript<String> enqueueScript;
    private final RedisScript<Long> promoteScript;
    private final RedisScript<String> startScript;
    private final RedisScript<Long> releaseScript;
    private final RedisScript<String> statusScript;

    public RedisQueueAdapter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.enqueueScript = RedisScript.of(new ClassPathResource("redis/enqueue.lua"), String.class);
        this.promoteScript = RedisScript.of(new ClassPathResource("redis/promote.lua"), Long.class);
        this.startScript = RedisScript.of(new ClassPathResource("redis/start.lua"), String.class);
        this.releaseScript = RedisScript.of(new ClassPathResource("redis/release.lua"), Long.class);
        this.statusScript = RedisScript.of(new ClassPathResource("redis/status.lua"), String.class);
    }

    @Override
    public EnqueueResult enqueue(long campaignId, long userId, int maxQueueSize) {
        String result = redisTemplate.execute(
            enqueueScript,
            List.of(queueKey(campaignId), hbKey(campaignId)),
            String.valueOf(userId),
            String.valueOf(nowSeconds()),
            String.valueOf(maxQueueSize)
        );
        return EnqueueResult.valueOf(result);
    }

    @Override
    public int promote(long campaignId, int capacity, long heartbeatTimeoutSeconds, long admitTtlSeconds) {
        Long result = redisTemplate.execute(
            promoteScript,
            List.of(queueKey(campaignId), hbKey(campaignId), admittedKey(campaignId), activeKey(campaignId)),
            String.valueOf(nowSeconds()),
            String.valueOf(capacity),
            String.valueOf(heartbeatTimeoutSeconds),
            String.valueOf(admitTtlSeconds)
        );
        return result == null ? 0 : result.intValue();
    }

    @Override
    public StartResult start(long campaignId, long userId, long admitTtlSeconds) {
        String result = redisTemplate.execute(
            startScript,
            List.of(admittedKey(campaignId), activeKey(campaignId)),
            String.valueOf(userId),
            String.valueOf(nowSeconds()),
            String.valueOf(admitTtlSeconds)
        );
        return StartResult.valueOf(result);
    }

    @Override
    public void release(long campaignId, long userId) {
        redisTemplate.execute(releaseScript, List.of(activeKey(campaignId)), String.valueOf(userId));
    }

    @Override
    public QueueStatusResult status(long campaignId, long userId, long admitTtlSeconds) {
        String result = redisTemplate.execute(
            statusScript,
            List.of(queueKey(campaignId), admittedKey(campaignId), activeKey(campaignId), hbKey(campaignId)),
            String.valueOf(userId),
            String.valueOf(nowSeconds()),
            String.valueOf(admitTtlSeconds)
        );
        if (result == null || result.equals("NONE")) {
            return QueueStatusResult.of(QueueStatus.NONE);
        }
        if (result.equals("ACTIVE")) {
            return QueueStatusResult.of(QueueStatus.ACTIVE);
        }
        if (result.equals("ADMITTED")) {
            return QueueStatusResult.of(QueueStatus.ADMITTED);
        }
        if (result.startsWith("QUEUED:")) {
            int position = Integer.parseInt(result.substring(7));
            return QueueStatusResult.queued(position);
        }
        return QueueStatusResult.of(QueueStatus.NONE);
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    static String queueKey(long campaignId) { return "queue:" + campaignId; }
    static String hbKey(long campaignId) { return "queue_hb:" + campaignId; }
    static String admittedKey(long campaignId) { return "admitted:" + campaignId; }
    static String activeKey(long campaignId) { return "active:" + campaignId; }
}
