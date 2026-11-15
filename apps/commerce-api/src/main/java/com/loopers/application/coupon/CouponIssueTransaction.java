package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueCancellationModel;
import com.loopers.domain.coupon.CouponIssueCancellationRepository;
import com.loopers.domain.coupon.CouponIssueJob;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 판단·발급 수 증가·쿠폰 저장은 한 TX다. Redis와 무관하게 DB가 총량·중복의 최종 방어선이다. */
@Component
@RequiredArgsConstructor
public class CouponIssueTransaction {
    private final CouponCampaignRepository campaigns;
    private final CouponRepository coupons;
    private final UserRepository users;
    private final CouponIssueCancellationRepository cancellations;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CouponIssueResult issue(Long userId, Long campaignId, CouponIssueExecution execution) {
        execution.observeTransaction();
        var existing = coupons.findByCampaignIdAndUserId(campaignId, userId);
        if (existing.isPresent()) return CouponIssueResult.existing(CouponInfo.from(existing.get()));
        var campaign = campaigns.find(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다."));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!campaigns.tryRecordIssue(campaignId, now)) throw rejection(campaign, now);
        CouponModel coupon = coupons.save(new CouponModel(userId, campaignId, now, campaign.getBenefitDescription()));
        return CouponIssueResult.created(CouponInfo.from(coupon));
    }

    /**
     * 작업 큐 Worker의 발급. 종료 판단과 발급 시각에 처리 시각 대신 접수 시각을 쓴다.
     * 종료 전에 "받음"이라고 답한 요청은 종료 뒤에 처리돼도 발급한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CouponIssueResult issueAccepted(Long userId, Long campaignId, Instant acceptedAt, String reservation,
                                           CouponIssueExecution execution) {
        execution.observeTransaction();
        var existing = coupons.findByCampaignIdAndUserId(campaignId, userId);
        if (existing.isPresent()) return CouponIssueResult.existing(CouponInfo.from(existing.get()));
        var campaign = campaigns.find(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다."));
        Instant issuedAt = acceptedAt.truncatedTo(ChronoUnit.MICROS);
        if (!campaigns.tryRecordIssue(campaignId, issuedAt)) throw rejection(campaign, issuedAt);
        CouponModel coupon = coupons.save(new CouponModel(userId, campaignId, issuedAt, campaign.getBenefitDescription(), reservation));
        return CouponIssueResult.created(CouponInfo.from(coupon));
    }

    /**
     * 작업 큐 Worker의 묶음 저장. 한 TX에서 사용자 확인과 기존 쿠폰 확인을 각각 조회 한 번으로 하고,
     * 새로 줄 쿠폰이 모두 한도 안이면 발급 수를 한 번에 올린 뒤(+N) 쿠폰을 INSERT 문 하나로 넣는다.
     * 한 번에 올릴 수 없으면(한도에 닿음 등) 쿠폰마다 조건부 UPDATE로 나눠, 들어갈 수 있는 만큼만 발급한다.
     * 확정 거절은 예외 없이 결과로 돌려준다. 예외가 나면 TX가 rollback 대상이 되어 다른 작업의 저장까지 되돌리기 때문이다.
     * 확인 순서는 취소 기록 → 기존 쿠폰 → 사용자다. 이미 확정한 결과(취소·발급)는 지금 조건이 바뀌어도 뒤집지 않는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<CouponIssueOutcome> issueAcceptedBatch(List<CouponIssueJob> jobs, CouponIssueExecution execution) {
        execution.observeTransaction();
        Map<CouponIssueJob, CouponIssueOutcome> outcomes = new HashMap<>();
        Set<Long> activeUsers = users.findActiveIds(jobs.stream().map(CouponIssueJob::userId).collect(Collectors.toSet()));
        Map<Long, List<CouponIssueJob>> byCampaign = jobs.stream()
            .collect(Collectors.groupingBy(CouponIssueJob::campaignId, LinkedHashMap::new, Collectors.toList()));
        for (var group : byCampaign.entrySet()) {
            issueCampaignBatch(group.getKey(), group.getValue(), activeUsers, outcomes);
        }
        return jobs.stream().map(outcomes::get).toList();
    }

    private void issueCampaignBatch(Long campaignId, List<CouponIssueJob> jobs, Set<Long> activeUsers,
                                    Map<CouponIssueJob, CouponIssueOutcome> outcomes) {
        var campaign = campaigns.find(campaignId);
        if (campaign.isEmpty()) {
            jobs.forEach(job -> outcomes.put(job, CouponIssueOutcome.rejected(job, "CAMPAIGN_NOT_FOUND")));
            return;
        }
        List<Long> userIds = jobs.stream().map(CouponIssueJob::userId).toList();
        Map<Long, String> cancelled = cancellations.findAll(campaignId, userIds).stream()
            .collect(Collectors.toMap(CouponIssueCancellationModel::getUserId, CouponIssueCancellationModel::getReason));
        Map<Long, CouponModel> existing = coupons.findAllByCampaignIdAndUserIds(campaignId, userIds)
            .stream().collect(Collectors.toMap(CouponModel::getUserId, coupon -> coupon));
        List<CouponIssueJob> candidates = new ArrayList<>();
        for (CouponIssueJob job : jobs) {
            if (cancelled.containsKey(job.userId())) {
                outcomes.put(job, CouponIssueOutcome.rejected(job, cancelled.get(job.userId())));
            } else if (existing.containsKey(job.userId())) {
                outcomes.put(job, CouponIssueOutcome.existing(job, CouponInfo.from(existing.get(job.userId()))));
            } else if (!activeUsers.contains(job.userId())) {
                outcomes.put(job, CouponIssueOutcome.rejected(job, "USER_NOT_FOUND"));
            } else {
                candidates.add(job);
            }
        }
        if (candidates.isEmpty()) return;
        // 종료 판단은 묶음에서 가장 늦은 접수 시각으로 한다. 그 시각이 종료 전이면 나머지도 종료 전이다.
        Instant latest = candidates.stream().map(job -> micros(job.acceptedAt())).max(Instant::compareTo).orElseThrow();
        if (campaigns.tryRecordIssues(campaignId, candidates.size(), latest)) {
            coupons.insertAll(candidates.stream().map(job -> newCoupon(job, campaign.get())).toList());
            candidates.forEach(job -> outcomes.put(job, CouponIssueOutcome.created(job, null)));
            return;
        }
        for (CouponIssueJob job : candidates) {
            Instant issuedAt = micros(job.acceptedAt());
            if (campaigns.tryRecordIssue(campaignId, issuedAt)) {
                coupons.insertAll(List.of(newCoupon(job, campaign.get())));
                outcomes.put(job, CouponIssueOutcome.created(job, null));
            } else {
                outcomes.put(job, CouponIssueOutcome.rejected(job, rejectionReason(campaign.get(), issuedAt)));
            }
        }
    }

    private static CouponModel newCoupon(CouponIssueJob job, CouponCampaignModel campaign) {
        return new CouponModel(job.userId(), job.campaignId(), micros(job.acceptedAt()), campaign.getBenefitDescription(),
            job.reservation());
    }

    private static Instant micros(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    private static String rejectionReason(CouponCampaignModel campaign, Instant at) {
        if (!campaign.isActive()) return "INACTIVE";
        if (!campaign.getEndAt().isAfter(at)) return "ENDED";
        return "SOLD_OUT";
    }

    // 원자 UPDATE 뒤 원인은 직전 조회 값으로 분류한다. 그 사이 상태 변경의 오류 분류는 달라질 수 있다.
    private static CoreException rejection(CouponCampaignModel campaign, Instant now) {
        if (!campaign.isActive()) return new CoreException(ErrorType.CONFLICT, CouponRejections.INACTIVE);
        if (!campaign.getEndAt().isAfter(now)) return new CoreException(ErrorType.CONFLICT, CouponRejections.ENDED);
        return new CoreException(ErrorType.CONFLICT, CouponRejections.SOLD_OUT);
    }
}
