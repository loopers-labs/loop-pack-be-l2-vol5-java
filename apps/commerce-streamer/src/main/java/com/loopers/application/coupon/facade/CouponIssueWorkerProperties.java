package com.loopers.application.coupon.facade;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 발급 worker 의 값. 모두 잠정값이고 실험으로 확정한다 (8주차 D-131, D-171) */
@ConfigurationProperties("coupon-issue.worker")
public record CouponIssueWorkerProperties(
    String instanceId,
    int threads,
    int batchSize,
    /** 일시 오류의 안쪽 재시도 횟수와 첫 간격(2배씩) (D-128) */
    int innerRetries,
    Duration innerBackoff,
    /** 재전달 backoff 의 첫 간격과 최대(2배씩) (D-129) */
    Duration redeliveryInitial,
    Duration redeliveryMax,
    /** 미분류 예외가 이 횟수에 닿으면 최종 실패 (D-126, D-127) */
    int unclassifiedLimit,
    /** PEL 전달 횟수가 이 값을 넘으면 poison 으로 보고 최종 실패 (D-173) */
    int poisonDeliveries,
    /** 이만큼 처리 중이면 죽은 worker 로 보고 회수 (D-119). 정상 처리 최대 시간보다 길어야 한다 */
    Duration claimMinIdle,
    /** 발급 수 재계산 간격 (D-142) */
    Duration recountInterval,
    /** 접수 뒤 이만큼 지나도 확정되지 않으면 정리한다. producer 전송 상한보다 길어야 한다 (D-134) */
    Duration pendingConfirmAfter
) {}
