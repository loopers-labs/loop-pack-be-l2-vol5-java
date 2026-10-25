package com.loopers.testpg.infrastructure;

import com.loopers.testpg.domain.PaymentScenario;
import com.loopers.testpg.domain.ScenarioSetting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryScenarioRepositoryTest {

    @DisplayName("시나리오를 등록하지 않은 주문은 기본 성공 시나리오를 쓰고, 등록한 주문은 마지막 등록 값을 쓴다.")
    @Test
    void returnsDefaultUntilRegisteredAndKeepsLatestPerOrder() {
        InMemoryScenarioRepository repository = new InMemoryScenarioRepository();

        assertThat(repository.find("LP-ORD-0000000001").scenario()).isEqualTo(PaymentScenario.SUCCESS);

        repository.register("LP-ORD-0000000001", ScenarioSetting.of(PaymentScenario.FAIL));
        repository.register("LP-ORD-0000000001", ScenarioSetting.of(PaymentScenario.SKIP_CALLBACK));

        assertThat(repository.find("LP-ORD-0000000001").scenario()).isEqualTo(PaymentScenario.SKIP_CALLBACK);
        assertThat(repository.find("LP-ORD-0000000002").scenario()).isEqualTo(PaymentScenario.SUCCESS);
    }
}
