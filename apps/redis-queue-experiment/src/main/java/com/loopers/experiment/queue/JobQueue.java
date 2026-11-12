package com.loopers.experiment.queue;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class JobQueue {

    private static final String QUEUE_KEY = "queue-semaphore-experiment:jobs";
    private static final String PERMITS_KEY = "queue-semaphore-experiment:permits";
    private static final String JOB_KEY_PREFIX = "queue-semaphore-experiment:job:";

    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('LLEN', KEYS[1]) >= tonumber(ARGV[1]) then
          return 0
        end
        redis.call('HSET', KEYS[2], 'status', 'QUEUED', 'submittedAt', ARGV[2], 'workloadType', ARGV[5])
        redis.call('EXPIRE', KEYS[2], ARGV[3])
        redis.call('LPUSH', KEYS[1], ARGV[4])
        return 1
        """, Long.class);

    private static final DefaultRedisScript<String> CLAIM_SCRIPT = new DefaultRedisScript<>("""
        if redis.call('SCARD', KEYS[2]) >= tonumber(ARGV[1]) then
          return ''
        end
        local jobId = redis.call('RPOP', KEYS[1])
        if not jobId then
          return ''
        end
        redis.call('SADD', KEYS[2], ARGV[2])
        local jobKey = ARGV[3] .. jobId
        redis.call('HSET', jobKey, 'status', 'PROCESSING', 'startedAt', ARGV[4])
        redis.call('EXPIRE', jobKey, ARGV[5])
        return jobId .. '|' .. ARGV[2] .. '|' .. redis.call('HGET', jobKey, 'workloadType')
        """, String.class);

    private static final DefaultRedisScript<Long> FINISH_SCRIPT = new DefaultRedisScript<>("""
        redis.call('HSET', KEYS[1], 'status', ARGV[1], 'completedAt', ARGV[2])
        if ARGV[3] ~= '' then
          redis.call('HSET', KEYS[1], 'error', ARGV[3])
        end
        redis.call('EXPIRE', KEYS[1], ARGV[4])
        return redis.call('SREM', KEYS[2], ARGV[5])
        """, Long.class);

    private final StringRedisTemplate redis;
    private final ExperimentProperties properties;

    public JobQueue(StringRedisTemplate redis, ExperimentProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public String enqueue(WorkloadType workloadType) {
        String jobId = UUID.randomUUID().toString();
        Long accepted = redis.execute(
            ENQUEUE_SCRIPT,
            List.of(QUEUE_KEY, jobKey(jobId)),
            String.valueOf(properties.maxQueueSize()),
            Instant.now().toString(),
            String.valueOf(properties.jobRetentionSeconds()),
            jobId,
            workloadType.name()
        );
        if (accepted == null || accepted == 0L) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "experiment queue is full");
        }
        return jobId;
    }

    public ClaimedJob claim() {
        String token = UUID.randomUUID().toString();
        String claimed = redis.execute(
            CLAIM_SCRIPT,
            List.of(QUEUE_KEY, PERMITS_KEY),
            String.valueOf(properties.semaphoreCapacity()),
            token,
            JOB_KEY_PREFIX,
            Instant.now().toString(),
            String.valueOf(properties.jobRetentionSeconds())
        );
        if (claimed == null || claimed.isEmpty()) {
            return null;
        }
        String[] parts = claimed.split("\\|", 3);
        return new ClaimedJob(parts[0], parts[1], WorkloadType.valueOf(parts[2]));
    }

    public void finish(ClaimedJob job, String status, String error) {
        redis.execute(
            FINISH_SCRIPT,
            List.of(jobKey(job.jobId()), PERMITS_KEY),
            status,
            Instant.now().toString(),
            error == null ? "" : error,
            String.valueOf(properties.jobRetentionSeconds()),
            job.permitToken()
        );
    }

    public JobView find(String jobId) {
        Map<Object, Object> values = redis.opsForHash().entries(jobKey(jobId));
        if (values.isEmpty()) {
            return null;
        }
        return new JobView(
            jobId,
            value(values, "status"),
            WorkloadType.valueOf(value(values, "workloadType")),
            value(values, "submittedAt"),
            value(values, "startedAt"),
            value(values, "completedAt"),
            value(values, "error")
        );
    }

    public QueueStats stats() {
        Long queued = redis.opsForList().size(QUEUE_KEY);
        Long active = redis.opsForSet().size(PERMITS_KEY);
        return new QueueStats(
            queued == null ? 0 : queued,
            active == null ? 0 : active,
            properties.semaphoreCapacity(),
            properties.workerCount()
        );
    }

    private String jobKey(String jobId) {
        return JOB_KEY_PREFIX + jobId;
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    public record ClaimedJob(String jobId, String permitToken, WorkloadType workloadType) {
    }
}
