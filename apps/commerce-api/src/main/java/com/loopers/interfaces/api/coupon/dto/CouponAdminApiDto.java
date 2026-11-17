package com.loopers.interfaces.api.coupon.dto;

import com.loopers.application.coupon.support.CouponExecution;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.util.List;

public final class CouponAdminApiDto {
    private CouponAdminApiDto() {}

    public record CloseRequest(String reason) {
        public void validate() { text(reason, "닫기 사유", 200); }
    }

    public record BootRequest(String expectedBootId) {
        public void validate() { text(expectedBootId, "boot ID", 100); }
    }

    public record PreviousSession(String serverId, Long connectionId) {
        CouponExecution.DbWork toWork() {
            text(serverId, "DB 서버 ID", 100);
            if (connectionId == null || connectionId <= 0) { invalid("DB 연결 ID는 양수여야 합니다."); }
            return new CouponExecution.DbWork(serverId, connectionId);
        }
    }

    public record VerifyRestartRequest(String expectedBootId, Boolean previousProcessStopped, String auditReference,
                                       Boolean initialDeployment, List<PreviousSession> previousSessions) {
        public List<CouponExecution.DbWork> validatedSessions() {
            text(expectedBootId, "boot ID", 100);
            text(auditReference, "운영 확인 기록", 500);
            if (previousProcessStopped == null || initialDeployment == null || previousSessions == null
                || previousSessions.size() > 1000 || previousSessions.stream().anyMatch(session -> session == null)) {
                invalid("종료 확인, 최초 배포 여부와 이전 세션 목록을 명시해야 합니다.");
            }
            return previousSessions.stream().map(PreviousSession::toWork).toList();
        }
    }

    public record RecoverRequest(String expectedBootId, List<String> executionIds) {
        public void validate() {
            text(expectedBootId, "boot ID", 100);
            if (executionIds == null || executionIds.isEmpty() || executionIds.size() > 1000) {
                invalid("복구할 실행 ID를 1~1000개 지정해야 합니다.");
            }
            executionIds.forEach(id -> text(id, "실행 ID", 200));
        }
    }

    private static void text(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            invalid(name + " 값이 없거나 허용 길이를 초과했습니다.");
        }
    }

    private static void invalid(String message) { throw new CoreException(ErrorType.BAD_REQUEST, message); }
}
