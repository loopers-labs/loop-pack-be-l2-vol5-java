package com.loopers.testpg.domain;

public interface ScenarioRepository {
    void register(String orderId, ScenarioSetting setting);

    // 등록하지 않은 주문은 기본 성공 시나리오를 쓴다.
    ScenarioSetting find(String orderId);
}
