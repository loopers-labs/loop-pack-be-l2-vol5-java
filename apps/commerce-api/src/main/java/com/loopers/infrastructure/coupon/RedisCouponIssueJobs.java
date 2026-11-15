package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponIssueJob;
import com.loopers.domain.coupon.CouponIssueJobs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 캠페인마다 Redis Stream 하나를 작업 큐로 쓴다. 등록은 판단 Lua(enqueue)가 하고, 여기서는 읽기·처리 완료만 한다.
 * 이 Worker가 가져갔지만 처리 완료(XACK)하지 않은 작업은 소비자 그룹의 pending 목록에 남아, 다음 poll에서 먼저 다시 읽는다.
 * Worker가 다른 이름으로 바뀌거나 여러 개가 되면 남의 pending을 넘겨받는 처리(XCLAIM)가 따로 필요하다.
 * 큐에는 만료를 걸지 않고, 접수가 끝나 남은 작업이 없을 때 poll이 정리한다.
 */
@Component
public class RedisCouponIssueJobs implements CouponIssueJobs {
    /** 작업 큐가 준비된 캠페인 ID. 종료된 캠페인도 남은 작업을 처리하려고 시각이 아니라 이 목록을 본다. */
    static final String QUEUED_CAMPAIGNS = "coupon-issue:queued-campaigns";
    private static final RedisScript<Long> RESET = RedisScript.of(new ClassPathResource("redis/coupon-queue-reset.lua"), Long.class);
    private static final RedisScript<Long> CLEANUP = RedisScript.of(new ClassPathResource("redis/coupon-queue-cleanup.lua"), Long.class);

    private final RedisTemplate<String, String> redis;
    private final String consumer;

    public RedisCouponIssueJobs(@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
        @Value("${commerce.coupon.issue-queue.consumer:worker-1}") String consumer) {
        this.redis = redis;
        this.consumer = consumer;
    }

    @Override
    public void reset(Long campaignId) {
        // 목록에 먼저 넣는다. 큐를 만든 뒤 목록에 넣기 전에 멈추면 처리되지 않는 큐가 생기기 때문이다.
        redis.opsForSet().add(QUEUED_CAMPAIGNS, campaignId.toString());
        redis.execute(RESET, List.of(RedisCouponIssueGate.jobsKey(campaignId)), RedisCouponIssueGate.JOB_GROUP);
    }

    @Override
    public List<CouponIssueJob> poll(int max) {
        Set<String> campaigns = redis.opsForSet().members(QUEUED_CAMPAIGNS);
        List<CouponIssueJob> jobs = new ArrayList<>();
        if (campaigns == null) return jobs;
        for (String campaign : campaigns) {
            Long campaignId = Long.valueOf(campaign);
            String key = RedisCouponIssueGate.jobsKey(campaignId);
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                // 큐가 없다. 판단 정보도 없으면 끝난 캠페인이라 목록에서 뺀다. 판단 정보만 있으면 준비 중일 수 있어 둔다.
                if (!Boolean.TRUE.equals(redis.hasKey(RedisCouponIssueGate.stockKey(campaignId)))) {
                    redis.opsForSet().remove(QUEUED_CAMPAIGNS, campaign);
                }
                continue;
            }
            List<CouponIssueJob> found = read(campaignId, key, ReadOffset.from("0"), max - jobs.size());
            if (found.isEmpty() && jobs.size() < max) {
                found = read(campaignId, key, ReadOffset.lastConsumed(), max - jobs.size());
            }
            if (found.isEmpty()) {
                cleanupIfFinished(campaignId, campaign);
            }
            jobs.addAll(found);
            if (jobs.size() >= max) break;
        }
        return jobs;
    }

    @Override
    public void acknowledge(CouponIssueJob job) {
        redis.opsForStream().acknowledge(RedisCouponIssueGate.jobsKey(job.campaignId()), RedisCouponIssueGate.JOB_GROUP, job.id());
    }

    @Override
    public void hold(CouponIssueJob job, String reason) {
        String value = job.userId() + "|" + job.reservation() + "|" + job.acceptedAt().toEpochMilli() + "|" + reason;
        redis.opsForHash().put(RedisCouponIssueGate.heldKey(job.campaignId()), job.id(), value);
        acknowledge(job);
    }

    /** 접수가 끝났고 남은 작업·보류 작업이 없으면 판단 정보와 큐를 지우고 목록에서 뺀다. */
    private void cleanupIfFinished(Long campaignId, String campaign) {
        Long removed = redis.execute(CLEANUP, List.of(RedisCouponIssueGate.stockKey(campaignId),
                RedisCouponIssueGate.usersKey(campaignId), RedisCouponIssueGate.jobsKey(campaignId),
                RedisCouponIssueGate.heldKey(campaignId)),
            RedisCouponIssueGate.JOB_GROUP, Long.toString(Instant.now().toEpochMilli()));
        if (Long.valueOf(1).equals(removed)) {
            redis.opsForSet().remove(QUEUED_CAMPAIGNS, campaign);
        }
    }

    private List<CouponIssueJob> read(Long campaignId, String key, ReadOffset offset, int count) {
        if (count <= 0) return List.of();
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
            Consumer.from(RedisCouponIssueGate.JOB_GROUP, consumer), StreamReadOptions.empty().count(count),
            StreamOffset.create(key, offset));
        if (records == null) return List.of();
        List<CouponIssueJob> jobs = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> value = record.getValue();
            jobs.add(new CouponIssueJob(record.getId().getValue(), campaignId, Long.valueOf((String) value.get("userId")),
                (String) value.get("reservation"), Instant.ofEpochMilli(Long.parseLong((String) value.get("acceptedAt")))));
        }
        return jobs;
    }
}
