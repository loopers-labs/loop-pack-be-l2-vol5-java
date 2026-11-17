package com.loopers.application.coupon.support;

import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.port.CouponAdmission;
import com.loopers.application.coupon.port.CouponExecutionEvidence;
import com.loopers.application.coupon.support.CouponAdmissionException.Code;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 모든 수용 상태 변경과 획득/등록을 동일 모니터에서 조율한다. DB 작업 중에도 close는 가능하다. */
@Slf4j
@Component
public class CouponAdmissionControl {
    public enum Recovery { RELEASED, ALREADY_RELEASED, NOT_FOUND, OTHER_BOOT, NOT_ENDED, RETURN_FAILED }
    public record RecoveryResult(String executionId, Recovery result) {}
    public record ExecutionView(String executionId, long campaignId, long userId, boolean ended,
                                boolean released, List<CouponExecution.Transaction> transactions) {}
    public record Status(String instanceId, String bootId, boolean open, String reason, int capacity, int available,
                         long running, long held, boolean restartVerified, List<ExecutionView> executions) {}

    public static final class Handle {
        private final CouponExecution execution;
        private final CouponAdmission.LocalPermit permit;
        private final CouponIssueCommand command;
        private boolean released;

        private Handle(CouponExecution execution, CouponAdmission.LocalPermit permit, CouponIssueCommand command) {
            this.execution = execution;
            this.permit = permit;
            this.command = command;
        }

        public CouponExecution execution() { return execution; }
    }

    private final CouponAdmission admission;
    private final CouponExecutionEvidence evidence;
    private final String instanceId = ProcessHandle.current().pid() + "@"
        + System.getenv().getOrDefault("COMPUTERNAME", System.getenv().getOrDefault("HOSTNAME", "local"));
    private final String bootId = UUID.randomUUID().toString();
    private final Map<String, Handle> handles = new LinkedHashMap<>();
    private boolean open;
    private boolean restartVerified;
    private String reason = "STARTUP_VERIFICATION_REQUIRED";

    public CouponAdmissionControl(CouponAdmission admission, CouponExecutionEvidence evidence) {
        this.admission = admission;
        this.evidence = evidence;
    }

    public synchronized Handle acquire(CouponIssueCommand command) {
        if (!open) {
            throw new CouponAdmissionException(Code.COUPON_ADMISSION_UNAVAILABLE);
        }
        CouponAdmission.LocalPermit permit = admission.tryAcquire()
            .orElseThrow(() -> new CouponAdmissionException(Code.COUPON_CAPACITY_EXCEEDED));
        String id = bootId + ":" + UUID.randomUUID();
        Handle handle = new Handle(new CouponExecution(id), permit, command);
        handles.put(id, handle);
        log.info("coupon instance={} execution={} campaign={} user={} stage=ACQUIRED",
            instanceId, id, command.campaignId(), command.userId());
        return handle;
    }

    public synchronized boolean finish(Handle handle, boolean uncertainFailure) {
        if (handles.get(handle.execution.id()) != handle) {
            throw new IllegalArgumentException("Unregistered coupon execution");
        }
        handle.execution.end(uncertainFailure);
        if (handle.execution.confirmed()) {
            release(handle);
        } else {
            close("OUTCOME_UNKNOWN");
        }
        log.info("coupon instance={} execution={} stage=ENDED released={} transactions={}",
            instanceId, handle.execution.id(), handle.released, handle.execution.transactions());
        return handle.released;
    }

    /** 재시도 허가는 close와 같은 경계에서 결정한다. 허가된 시도는 진행 실행으로 취급한다. */
    public synchronized void authorizeRetry(Handle handle) {
        if (!open || handles.get(handle.execution.id()) != handle || handle.released || handle.execution.ended()) {
            throw new CouponAdmissionException(Code.COUPON_ADMISSION_UNAVAILABLE);
        }
        if (!handle.execution.transactionsConfirmed()) {
            throw new CouponAdmissionException(Code.COUPON_ISSUE_OUTCOME_UNKNOWN);
        }
    }

    public synchronized void close(String closeReason) {
        open = false;
        reason = closeReason;
        log.info("coupon instance={} boot={} stage=CLOSED reason={}", instanceId, bootId, reason);
    }

    /** 운용자가 이전 process 종료를 확인하고, adapter도 실제 DB 종료/정합성을 확인해야 한다. */
    public synchronized void verifyRestart(String expectedBootId, boolean previousProcessStopped, String auditReference,
                                           boolean initialDeployment, List<CouponExecution.DbWork> previousSessions) {
        requireBoot(expectedBootId);
        // 닫힌 인스턴스의 재검증은 이전 성공 증거를 대체한다. 실패 뒤 과거 증거로 open하지 않는다.
        if (!open) {
            restartVerified = false;
        }
        if (open || !previousProcessStopped || auditReference == null || auditReference.isBlank()
            || previousSessions == null || initialDeployment != previousSessions.isEmpty()
            || handles.values().stream().anyMatch(handle -> !handle.released)
            || !restartReady(List.copyOf(previousSessions))) {
            throw new CouponAdmissionException(Code.COUPON_RECOVERY_REJECTED);
        }
        restartVerified = true;
        // 이전 세션 목록의 완전성과 이전 process 종료는 운영 로그 확인 책임이다. 빈 registry로 추정하지 않는다.
        log.info("coupon instance={} boot={} stage=RESTART_VERIFIED initial={} previousSessions={} audit={}",
            instanceId, bootId, initialDeployment, previousSessions, auditReference);
    }

    public synchronized void open(String expectedBootId) {
        requireBoot(expectedBootId);
        if (!restartVerified || handles.values().stream().anyMatch(handle -> !handle.released)) {
            throw new CouponAdmissionException(Code.COUPON_RECOVERY_REJECTED);
        }
        open = true;
        reason = "EXPLICIT_OPEN";
        log.info("coupon instance={} boot={} stage=OPEN", instanceId, bootId);
    }

    public synchronized List<RecoveryResult> recover(String expectedBootId, List<String> executionIds) {
        if (open) {
            throw new CouponAdmissionException(Code.COUPON_RECOVERY_REJECTED);
        }
        List<RecoveryResult> results = new ArrayList<>();
        for (String id : executionIds) {
            Recovery result;
            Handle handle = handles.get(id);
            if (!bootId.equals(expectedBootId) || id == null || !id.startsWith(bootId + ":")) {
                result = Recovery.OTHER_BOOT;
            } else if (handle == null) {
                result = Recovery.NOT_FOUND;
            } else if (handle.released) {
                result = Recovery.ALREADY_RELEASED;
            } else if (!handle.execution.ended() || !dbEnded(handle)) {
                result = Recovery.NOT_ENDED;
            } else {
                release(handle);
                result = handle.released ? Recovery.RELEASED : Recovery.RETURN_FAILED;
            }
            results.add(new RecoveryResult(id, result));
            log.info("coupon instance={} boot={} execution={} stage=RECOVER result={}", instanceId, bootId, id, result);
        }
        return List.copyOf(results);
    }

    public synchronized Status status() {
        List<ExecutionView> executions = handles.values().stream().map(handle -> new ExecutionView(
            handle.execution.id(), handle.command.campaignId(), handle.command.userId(), handle.execution.ended(),
            handle.released, handle.execution.transactions())).toList();
        long running = executions.stream().filter(execution -> !execution.ended()).count();
        long held = executions.stream().filter(execution -> execution.ended() && !execution.released()).count();
        return new Status(instanceId, bootId, open, reason, admission.capacity(), admission.available(), running, held,
            restartVerified, executions);
    }

    private boolean dbEnded(Handle handle) {
        try {
            List<CouponExecution.Transaction> transactions = handle.execution.transactions();
            return !transactions.isEmpty() && transactions.stream().allMatch(tx ->
                tx.work() != null && evidence.ended(tx.work(), handle.command.campaignId()));
        } catch (RuntimeException failure) {
            // TX 프록시의 시작/종료 예외도 adapter 본문 밖에서 발생할 수 있다. 다른 ID의 복구는 계속한다.
            log.warn("coupon instance={} execution={} stage=RECOVERY_EVIDENCE_UNAVAILABLE",
                instanceId, handle.execution.id(), failure);
            return false;
        }
    }

    private boolean restartReady(List<CouponExecution.DbWork> previousSessions) {
        try {
            return evidence.readyAfterRestart(previousSessions);
        } catch (RuntimeException failure) {
            log.warn("coupon instance={} boot={} stage=RESTART_EVIDENCE_UNAVAILABLE", instanceId, bootId, failure);
            restartVerified = false;
            close("RESTART_EVIDENCE_UNAVAILABLE");
            return false;
        }
    }

    private void release(Handle handle) {
        if (!handle.released) {
            handle.released = handle.permit.release();
            if (!handle.released) {
                close("PERMIT_RETURN_UNCONFIRMED");
            }
        }
        // 미반환 실행은 보존하고 최근 반환 이력만 제한한다.
        if (handles.size() > 1000) {
            handles.entrySet().removeIf(entry -> handles.size() > 1000 && entry.getValue().released);
        }
    }

    private void requireBoot(String expectedBootId) {
        if (!bootId.equals(expectedBootId)) {
            throw new CouponAdmissionException(Code.COUPON_RECOVERY_REJECTED);
        }
    }
}
