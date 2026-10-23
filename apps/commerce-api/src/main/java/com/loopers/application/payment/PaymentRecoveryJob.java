package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentCallbackInbox;
import com.loopers.domain.payment.PaymentCallbackInboxRepository;
import com.loopers.domain.payment.PaymentCallbackInboxService;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 결제 회복 작업 한 회차 — 수신함과 미종결 결제를 같은 작업으로 처리한다(docs/week5/design.md D-040).
 * 트랜잭션이 없다. 조회는 트랜잭션 밖, 반영은 TX B, 수신함 상태 기록은 짧은 TX다.
 *
 * - 처리 단위는 결제 시도다. 같은 시도의 callback 여러 통은 한 번만 조회한다(D-041).
 * - 한 회차는 처리할 차례인 시도가 남아 있는 동안 묶음(batch) 단위로 계속 처리한다. PG 조회는 초당 N건을 넘지 않는다(D-047).
 *   상한을 회차당 건수로 걸면 깨어나는 순간 몰려가고 나머지 시간은 쉬어, PG 부담은 크고 처리량은 작다(부하 측정 단계 0).
 * - 묶음마다 발송 시작이 오래된 시도부터 처리한다(D-034의 순서는 그대로).
 * - 시도는 수신함 본문의 키로 찾지만, PG 조회는 저장된 시도의 키로 한다 — 본문을 믿지 않는다.
 * - 조회 실패는 다음 회차에 다시, 세 번째 실패에서 포기한다(D-042). 이 경로는 조회를 재시도하지 않는다(D-028).
 * - 조회할 때마다 시도의 다음 확인 일정을 적는다 — 0건이면 간격을 늘리고, 물어보지 못했으면 3분 뒤 다시(D-043, D-044).
 */
@Slf4j
@Component
public class PaymentRecoveryJob {

    /** 한 묶음에 읽는 수신함 행의 상한. 같은 시도의 callback이 겹칠 수 있어 묶음 크기보다 넉넉하게 둔다. */
    private static final int INBOX_SCAN_FACTOR = 5;

    private final PaymentCallbackInboxRepository paymentCallbackInboxRepository;
    private final PaymentCallbackInboxService paymentCallbackInboxService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentReconciler paymentReconciler;
    private final PaymentResultApplier paymentResultApplier;
    private final Duration interval;
    private final int batchSize;
    private final RateLimiter lookupRateLimiter;

    @Autowired
    public PaymentRecoveryJob(
        PaymentCallbackInboxRepository paymentCallbackInboxRepository,
        PaymentCallbackInboxService paymentCallbackInboxService,
        PaymentAttemptRepository paymentAttemptRepository,
        PaymentReconciler paymentReconciler,
        PaymentResultApplier paymentResultApplier,
        @Value("${payment.recovery.interval:PT3M}") Duration interval,
        @Value("${payment.recovery.batch-size:100}") int batchSize,
        @Value("${payment.recovery.lookups-per-second:20}") int lookupsPerSecond
    ) {
        this.paymentCallbackInboxRepository = paymentCallbackInboxRepository;
        this.paymentCallbackInboxService = paymentCallbackInboxService;
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.paymentReconciler = paymentReconciler;
        this.paymentResultApplier = paymentResultApplier;
        this.interval = interval;
        this.batchSize = batchSize;
        // 1초 주기마다 허가 N개가 다시 채워지고(고정 주기), 조회 한 번에 하나를 쓴다. 다 쓰면 다음 주기까지 기다린다.
        this.lookupRateLimiter = RateLimiter.of("pg-recovery-lookup", RateLimiterConfig.custom()
            .limitForPeriod(lookupsPerSecond)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ofSeconds(10))
            .build());
    }

    public void runOnce(ZonedDateTime now) {
        runOnce(now, Integer.MAX_VALUE);
    }

    /**
     * 회차를 시작한 시각에 처리할 차례였던 시도가 없을 때까지 묶음 단위로 처리한다.
     * "차례인가"를 판단하는 시각도, 다음 확인 시각을 적는 기준도 회차 시작 시각 하나다 — 이 회차에서 실패해 미뤄진 시도(시작 + 3분)는
     * 같은 회차에 다시 차례가 되지 않는다. 묶음마다 시각이 흐르게 두면 회차가 재시도 간격보다 길어질 때 먼저 실패한 행이 다시 차례가 돼
     * 수신함 읽기 창을 채우고, 뒤쪽 시도는 한 번도 조회되지 않았다(부하 측정 단계 3). 길어진 회차 뒤에는 다음 회차가 미뤄진 시도를 곧바로 집는다.
     * 회차 도중 들어온 callback은 다음 회차가 맡는다(D-040).
     * maxLookups는 테스트가 순서를 적은 건수로 확인하려고 둔 조회 수 상한이다.
     */
    void runOnce(ZonedDateTime now, int maxLookups) {
        Set<Long> processedThisRun = new HashSet<>();
        int lookups = 0;
        while (lookups < maxLookups) {
            List<Candidate> batch = nextBatch(now, processedThisRun);
            if (batch.isEmpty()) {
                return;
            }
            for (Candidate candidate : batch) {
                PaymentAttempt attempt = candidate.attempt();
                processedThisRun.add(attempt.getId());
                if (attempt.getStatus().isTerminal() && !attempt.isWatched(now)) {
                    // 이미 다른 입구(고객 reconcile 등)가 끝냈다 — 다시 물을 필요가 없다. 감시 중인 시도(D-013)는 계속 묻는다.
                    paymentCallbackInboxService.markDone(candidate.inboxIds(), attempt.getId(), now);
                    continue;
                }
                if (lookups >= maxLookups) {
                    return;
                }
                lookups++;
                process(candidate, now);
            }
        }
    }

    /**
     * 처리할 차례인 수신함 행의 시도와 다음 확인 시각이 지난 미종결 시도를 모아(같은 시도는 하나로, D-041) 오래된 순으로 돌려준다.
     * 이번 회차에 이미 처리한 시도는 빼 — 기록이 실패해 계속 차례로 남는 시도가 회차를 끝나지 않게 만들지 않도록.
     */
    private List<Candidate> nextBatch(ZonedDateTime now, Set<Long> processedThisRun) {
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        List<Long> unmatched = new ArrayList<>();
        for (PaymentCallbackInbox inbox : paymentCallbackInboxRepository.findDue(now, batchSize * INBOX_SCAN_FACTOR)) {
            Optional<PaymentAttempt> attempt = findAttempt(inbox);
            if (attempt.isEmpty()) {
                unmatched.add(inbox.getId());
                continue;
            }
            if (processedThisRun.contains(attempt.get().getId())) {
                continue;
            }
            candidates.computeIfAbsent(attempt.get().getId(), id -> new Candidate(attempt.get(), new ArrayList<>()))
                .inboxIds().add(inbox.getId());
        }
        if (!unmatched.isEmpty()) {
            paymentCallbackInboxService.markUnmatched(unmatched, now);
        }
        for (PaymentAttempt attempt : paymentAttemptRepository.findDueForCheck(now, batchSize)) {
            if (!processedThisRun.contains(attempt.getId())) {
                candidates.computeIfAbsent(attempt.getId(), id -> new Candidate(attempt, new ArrayList<>()));
            }
        }
        return candidates.values().stream()
            .sorted(Comparator.comparing((Candidate candidate) -> candidate.attempt().getSendStartedAt())
                .thenComparing(candidate -> candidate.attempt().getId()))
            .limit(batchSize)
            .toList();
    }

    private void process(Candidate candidate, ZonedDateTime now) {
        PaymentAttempt attempt = candidate.attempt();
        if (!lookupRateLimiter.acquirePermission()) {
            // 정한 시간 안에 차례를 얻지 못했다 — 조회하지 않고 다음 회차로 넘긴다.
            return;
        }
        PaymentReconciler.Outcome outcome;
        try {
            outcome = paymentReconciler.reconcile(
                attempt.getOrderId(), attempt.getProviderOrderId(), attempt.getTransactionKey(), LookupRetry.NONE);
        } catch (RuntimeException e) {
            // 반영 중 예상하지 못한 오류 — 이 시도만 다음 회차로 미루고 나머지 시도는 계속 처리한다.
            log.error("결제 회복 처리 실패: attemptId={}", attempt.getId(), e);
            paymentCallbackInboxService.markFailed(candidate.inboxIds(), attempt.getId(),
                e.getClass().getSimpleName(), now, now.plus(interval));
            return;
        }
        paymentResultApplier.recordLookup(attempt.getOrderId(), outcome, now);
        if (outcome == PaymentReconciler.Outcome.UNAVAILABLE) {
            paymentCallbackInboxService.markFailed(candidate.inboxIds(), attempt.getId(), "PG 조회 실패", now, now.plus(interval));
            return;
        }
        paymentCallbackInboxService.markDone(candidate.inboxIds(), attempt.getId(), now);
    }

    private Optional<PaymentAttempt> findAttempt(PaymentCallbackInbox inbox) {
        if (inbox.getTransactionKey() != null) {
            Optional<PaymentAttempt> byKey = paymentAttemptRepository.findByTransactionKey(inbox.getTransactionKey());
            if (byKey.isPresent()) {
                return byKey;
            }
        }
        if (inbox.getProviderOrderId() != null) {
            return paymentAttemptRepository.findByProviderOrderId(inbox.getProviderOrderId());
        }
        return Optional.empty();
    }

    private record Candidate(PaymentAttempt attempt, List<Long> inboxIds) {}
}
