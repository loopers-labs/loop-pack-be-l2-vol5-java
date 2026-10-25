package com.loopers.testpg.interfaces;

import com.loopers.testpg.domain.LookupScenario;

public class LookupControlV1Dto {
    public record SettingRequest(Long delayMillis, Integer failureCount, Integer failureStatus) {
        public LookupScenario toScenario() {
            if (delayMillis == null) {
                throw new IllegalArgumentException("조회 지연은 필수입니다.");
            }
            return new LookupScenario(delayMillis, failureCount == null ? 0 : failureCount,
                failureStatus == null ? 503 : failureStatus);
        }
    }

    public record SettingResponse(long delayMillis, int failuresRemaining, int failureStatus) {
        public static SettingResponse from(LookupScenario scenario) {
            return new SettingResponse(scenario.delayMillis(), scenario.failureCount(), scenario.failureStatus());
        }
    }
}
