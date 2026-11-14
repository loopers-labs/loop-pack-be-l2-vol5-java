package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponIssueQueue;
import com.loopers.application.coupon.CouponIssueQueueClaim;
import com.loopers.application.coupon.CouponIssueQueueCleanup;
import com.loopers.application.coupon.CouponIssueQueueRecord;
import com.loopers.application.coupon.CouponIssueQueueResubmission;
import com.loopers.application.coupon.CouponIssueQueueScriptException;
import com.loopers.application.coupon.CouponIssueQueueSubmission;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponIssueRequest;
import com.loopers.domain.coupon.CouponIssueRequestReason;
import com.loopers.domain.coupon.CouponIssueRequestStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * primary Redis에 대기열을 저장한다. 상태를 바꾸는 연산은 Lua 한 번으로 처리하고,
 * 읽은 작업 정보는 도메인 복원 규칙으로 검증한다.
 */
@Component
public class RedisCouponIssueQueue implements CouponIssueQueue {

    public static final String WAITING_KEY = "coupon:issue:queue:waiting";
    public static final String PROCESSING_KEY = "coupon:issue:queue:processing";
    public static final String JOBS_KEY = "coupon:issue:queue:jobs";
    public static final String REQUESTS_KEY = "coupon:issue:queue:requests";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> submitScript;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> claimScript;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> recordScript;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> resubmitScript;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> clearCampaignScript;

    public RedisCouponIssueQueue(
        @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redisTemplate,
        ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.submitScript = listScript("redis/coupon-queue-submit.lua");
        this.claimScript = listScript("redis/coupon-queue-claim.lua");
        this.recordScript = listScript("redis/coupon-queue-record.lua");
        this.resubmitScript = listScript("redis/coupon-queue-resubmit.lua");
        this.clearCampaignScript = listScript("redis/coupon-queue-clear-campaign.lua");
    }

    @Override
    public CouponIssueQueueSubmission submit(CouponIssueRequest request, int capacity) {
        String json = toJson(request);
        List<?> result = redis(() -> redisTemplate.execute(submitScript,
            List.of(REQUESTS_KEY, JOBS_KEY, WAITING_KEY, PROCESSING_KEY),
            requestField(request.getCampaignId(), request.getUserId()), request.getRequestId(), json,
            Integer.toString(capacity)));
        if (result == null || result.isEmpty()) {
            throw new CouponIssueQueueScriptException("대기열 접수 결과가 비어 있습니다.", null);
        }
        return switch (String.valueOf(result.get(0))) {
            case "CREATED" -> new CouponIssueQueueSubmission.Created(fromJson(stringAt(result, 1)));
            case "EXISTING" -> new CouponIssueQueueSubmission.Existing(fromJson(stringAt(result, 1)));
            case "FULL" -> new CouponIssueQueueSubmission.Full();
            default -> throw new CouponIssueQueueScriptException("알 수 없는 대기열 접수 결과입니다.", null);
        };
    }

    @Override
    public Optional<CouponIssueRequest> findById(String requestId) {
        Object json = redis(() -> redisTemplate.opsForHash().get(JOBS_KEY, requestId));
        return Optional.ofNullable(json).map(value -> fromJson(String.valueOf(value)));
    }

    @Override
    public Optional<CouponIssueRequest> findByCampaignIdAndUserId(Long campaignId, Long userId) {
        Object requestId = redis(() -> redisTemplate.opsForHash().get(REQUESTS_KEY, requestField(campaignId, userId)));
        if (requestId == null) {
            return Optional.empty();
        }
        return Optional.of(findById(String.valueOf(requestId))
            .orElseThrow(() -> new CouponIssueQueueScriptException("중복 접수 정보에 연결된 작업 정보가 없습니다.", null)));
    }

    @Override
    public boolean hasWaiting() {
        Long size = redis(() -> redisTemplate.opsForList().size(WAITING_KEY));
        if (size == null) {
            throw new CouponIssueQueueScriptException("대기 List 길이를 읽지 못했습니다.", null);
        }
        return size > 0;
    }

    @Override
    public CouponIssueQueueClaim claim(String executionToken) {
        // 빈 token은 Lua 입력 검증에서 거절한다.
        String token = executionToken == null ? "" : executionToken;
        List<?> result = redis(() -> redisTemplate.execute(claimScript,
            List.of(WAITING_KEY, PROCESSING_KEY, JOBS_KEY), token));
        if (result == null || result.isEmpty()) {
            throw new CouponIssueQueueScriptException("대기열 가져오기 결과가 비어 있습니다.", null);
        }
        return switch (String.valueOf(result.get(0))) {
            case "CLAIMED" -> new CouponIssueQueueClaim.Claimed(fromJson(stringAt(result, 1)));
            case "EMPTY" -> new CouponIssueQueueClaim.Empty();
            default -> throw new CouponIssueQueueScriptException("알 수 없는 대기열 가져오기 결과입니다.", null);
        };
    }

    @Override
    public CouponIssueQueueRecord recordSucceeded(String requestId, String executionToken, Long couponId) {
        return record(requestId, executionToken, CouponIssueRequestStatus.SUCCEEDED,
            couponId == null ? "" : String.valueOf(couponId), "");
    }

    @Override
    public CouponIssueQueueRecord recordFailed(String requestId, String executionToken, CouponIssueRequestReason reason) {
        return record(requestId, executionToken, CouponIssueRequestStatus.FAILED, "",
            reason == null ? "" : reason.name());
    }

    @Override
    public CouponIssueQueueRecord recordUnknown(String requestId, String executionToken) {
        return record(requestId, executionToken, CouponIssueRequestStatus.UNKNOWN, "",
            CouponIssueRequestReason.DB_RESULT_UNKNOWN.name());
    }

    private CouponIssueQueueRecord record(String requestId, String executionToken, CouponIssueRequestStatus target,
                                          String couponId, String reasonCode) {
        // 빈 입력은 Lua 입력 검증에서 거절한다.
        List<?> result = redis(() -> redisTemplate.execute(recordScript, List.of(JOBS_KEY, PROCESSING_KEY),
            requestId == null ? "" : requestId, executionToken == null ? "" : executionToken, target.name(),
            couponId, reasonCode));
        if (result == null || result.isEmpty()) {
            throw new CouponIssueQueueScriptException("대기열 결과 기록 응답이 비어 있습니다.", null);
        }
        CouponIssueRequest current = fromJson(stringAt(result, 1));
        return switch (String.valueOf(result.get(0))) {
            case "APPLIED" -> new CouponIssueQueueRecord.Applied(current);
            case "ALREADY_APPLIED" -> new CouponIssueQueueRecord.AlreadyApplied(current);
            case "REJECTED" -> new CouponIssueQueueRecord.Rejected(current);
            default -> throw new CouponIssueQueueScriptException("알 수 없는 대기열 결과 기록 응답입니다.", null);
        };
    }

    @Override
    public CouponIssueQueueResubmission requeueFailed(String requestId, CouponIssueRequestReason expectedReason,
                                                      int capacity) {
        return resubmit(requestId, expectedReason, "REQUEUE", Integer.toString(capacity), "");
    }

    @Override
    public CouponIssueQueueResubmission markFailedSucceeded(String requestId, CouponIssueRequestReason expectedReason,
                                                            Long couponId) {
        return resubmit(requestId, expectedReason, "SUCCEED", "", couponId == null ? "" : String.valueOf(couponId));
    }

    private CouponIssueQueueResubmission resubmit(String requestId, CouponIssueRequestReason expectedReason,
                                                  String mode, String capacity, String couponId) {
        // 빈 입력과 업무 거절 사유는 Lua 입력 검증에서 거절한다.
        List<?> result = redis(() -> redisTemplate.execute(resubmitScript,
            List.of(JOBS_KEY, WAITING_KEY, PROCESSING_KEY), requestId == null ? "" : requestId,
            expectedReason == null ? "" : expectedReason.name(), mode, capacity, couponId));
        if (result == null || result.isEmpty()) {
            throw new CouponIssueQueueScriptException("대기열 재접수 응답이 비어 있습니다.", null);
        }
        CouponIssueRequest current = fromJson(stringAt(result, 1));
        return switch (String.valueOf(result.get(0))) {
            case "REQUEUED" -> new CouponIssueQueueResubmission.Requeued(current);
            case "SUCCEEDED" -> new CouponIssueQueueResubmission.Succeeded(current);
            case "CHANGED" -> new CouponIssueQueueResubmission.Changed(current);
            case "FULL" -> new CouponIssueQueueResubmission.Full(current);
            default -> throw new CouponIssueQueueScriptException("알 수 없는 대기열 재접수 응답입니다.", null);
        };
    }

    @Override
    public CouponIssueQueueCleanup clearCampaign(Long campaignId) {
        List<?> result = redis(() -> redisTemplate.execute(clearCampaignScript,
            List.of(REQUESTS_KEY, JOBS_KEY, WAITING_KEY, PROCESSING_KEY, RedisCouponIssuePermits.PERMITS_KEY),
            campaignId == null ? "" : String.valueOf(campaignId)));
        if (result == null || result.isEmpty()) {
            throw new CouponIssueQueueScriptException("캠페인 대기열 정리 응답이 비어 있습니다.", null);
        }
        return switch (String.valueOf(result.get(0))) {
            case "CLEARED" -> new CouponIssueQueueCleanup.Cleared(Integer.parseInt(stringAt(result, 1)));
            case "BLOCKED" -> new CouponIssueQueueCleanup.Blocked(stringAt(result, 1));
            default -> throw new CouponIssueQueueScriptException("알 수 없는 캠페인 대기열 정리 응답입니다.", null);
        };
    }

    static String requestField(Long campaignId, Long userId) {
        return campaignId + ":" + userId;
    }

    private String toJson(CouponIssueRequest request) {
        StoredJob stored = new StoredJob(request.getRequestId(), String.valueOf(request.getCampaignId()),
            String.valueOf(request.getUserId()), request.getStatus().name(), request.getRequestedAt().toString(),
            request.getCouponId() == null ? null : String.valueOf(request.getCouponId()),
            request.getReason() == null ? null : request.getReason().name(), request.getExecutionToken());
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (Exception exception) {
            throw new CouponIssueQueueScriptException("대기열 작업 정보를 저장 형식으로 바꾸지 못했습니다.", exception);
        }
    }

    private CouponIssueRequest fromJson(String json) {
        try {
            StoredJob stored = objectMapper.readValue(json, StoredJob.class);
            return CouponIssueRequest.reconstitute(stored.requestId(), Long.valueOf(stored.campaignId()),
                Long.valueOf(stored.userId()), CouponIssueRequestStatus.valueOf(stored.status()),
                Instant.parse(stored.requestedAt()),
                stored.couponId() == null ? null : Long.valueOf(stored.couponId()),
                stored.reasonCode() == null ? null : CouponIssueRequestReason.valueOf(stored.reasonCode()),
                stored.executionToken());
        } catch (Exception exception) {
            throw new CouponIssueQueueScriptException("저장된 대기열 작업 정보가 올바르지 않습니다.", exception);
        }
    }

    private static String stringAt(List<?> result, int index) {
        if (result.size() <= index || result.get(index) == null) {
            throw new CouponIssueQueueScriptException("대기열 결과에 작업 정보가 없습니다.", null);
        }
        return String.valueOf(result.get(index));
    }

    private static <T> T redis(Supplier<T> command) {
        try {
            return command.get();
        } catch (CouponIssueQueueScriptException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw RedisCouponIssueQueueExceptionTranslator.translate(exception);
        }
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> listScript(String location) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(List.class);
        return script;
    }

    /**
     * Redis에 저장하는 작업 정보. Lua가 숫자 정밀도를 바꾸지 않도록 ID와 시각은 문자열로 저장한다.
     */
    private record StoredJob(String requestId, String campaignId, String userId, String status, String requestedAt,
                             String couponId, String reasonCode, String executionToken) {
    }
}
