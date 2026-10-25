package com.loopers.testpg.domain;

// 테스트 제어 API로 주문별로 지정하는 PG 동작이다. 고객 결제 입력으로는 고르지 않는다.
public record ScenarioSetting(PaymentScenario scenario, Integer responseStatus, String responseBody,
                              boolean createTransaction) {

    public ScenarioSetting {
        if (scenario == null) {
            throw new IllegalArgumentException("시나리오는 필수입니다.");
        }
        if (scenario == PaymentScenario.CREATE_RESPONSE_OVERRIDE
            && (responseStatus == null || responseStatus < 100 || responseStatus > 599 || responseBody == null)) {
            throw new IllegalArgumentException("응답 대체 시나리오에는 HTTP 상태와 본문이 필요합니다.");
        }
    }

    public static ScenarioSetting defaultSetting() {
        return of(PaymentScenario.SUCCESS);
    }

    public static ScenarioSetting of(PaymentScenario scenario) {
        if (scenario == PaymentScenario.CREATE_RESPONSE_OVERRIDE) {
            throw new IllegalArgumentException("응답 대체 시나리오는 override로 만든다.");
        }
        return new ScenarioSetting(scenario, null, null, scenario != PaymentScenario.REJECT_BEFORE_CREATE);
    }

    public static ScenarioSetting override(int status, String body, boolean createTransaction) {
        return new ScenarioSetting(PaymentScenario.CREATE_RESPONSE_OVERRIDE, status, body, createTransaction);
    }

    public boolean createsTransaction() {
        return createTransaction;
    }

    public PaymentStatus result() {
        return scenario == PaymentScenario.FAIL ? PaymentStatus.FAILED : PaymentStatus.SUCCESS;
    }

    public int callbackCount() {
        return switch (scenario) {
            case SKIP_CALLBACK -> 0;
            case DUPLICATE_CALLBACK -> 2;
            default -> 1;
        };
    }

    public boolean dropsResponse() {
        return scenario == PaymentScenario.DROP_RESPONSE;
    }
}
