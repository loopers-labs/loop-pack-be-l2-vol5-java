package com.loopers.testpg.interfaces;

import com.loopers.testpg.application.OrderStatsInfo;
import com.loopers.testpg.domain.PaymentScenario;
import com.loopers.testpg.domain.ScenarioSetting;

public class TestControlV1Dto {
    public record ScenarioRequest(PaymentScenario scenario, Integer responseStatus, String responseBody,
                                  Boolean createTransaction) {
        public ScenarioSetting toSetting() {
            if (scenario == PaymentScenario.CREATE_RESPONSE_OVERRIDE) {
                if (responseStatus == null || createTransaction == null) {
                    throw new IllegalArgumentException("응답 대체 시나리오에는 상태와 거래 생성 여부가 필요합니다.");
                }
                return ScenarioSetting.override(responseStatus, responseBody, createTransaction);
            }
            return ScenarioSetting.of(scenario);
        }
    }

    public record CompleteResponse(int completed) {}

    public record StatsResponse(int createRequests, int lookupRequests, int callbackAttempts, int transactions) {
        public static StatsResponse from(OrderStatsInfo info) {
            return new StatsResponse(info.createRequests(), info.lookupRequests(), info.callbackAttempts(),
                info.transactions());
        }
    }
}
