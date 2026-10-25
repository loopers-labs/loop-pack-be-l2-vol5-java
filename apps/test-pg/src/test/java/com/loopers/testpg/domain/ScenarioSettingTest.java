package com.loopers.testpg.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ScenarioSettingTest {

    @DisplayName("기본 시나리오는 거래를 만들어 SUCCESS로 처리하고 callback을 한 번 보낸다.")
    @Test
    void defaultsToSuccessWithOneCallback() {
        ScenarioSetting setting = ScenarioSetting.defaultSetting();

        assertThat(setting.scenario()).isEqualTo(PaymentScenario.SUCCESS);
        assertThat(setting.createsTransaction()).isTrue();
        assertThat(setting.result()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(setting.callbackCount()).isEqualTo(1);
        assertThat(setting.dropsResponse()).isFalse();
    }

    @DisplayName("FAIL은 FAILED로 처리하고, SKIP_CALLBACK은 callback을 보내지 않으며, DUPLICATE_CALLBACK은 두 번 보낸다.")
    @Test
    void decidesResultAndCallbackCountByScenario() {
        assertThat(ScenarioSetting.of(PaymentScenario.FAIL).result()).isEqualTo(PaymentStatus.FAILED);
        assertThat(ScenarioSetting.of(PaymentScenario.FAIL).callbackCount()).isEqualTo(1);
        assertThat(ScenarioSetting.of(PaymentScenario.SKIP_CALLBACK).result()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(ScenarioSetting.of(PaymentScenario.SKIP_CALLBACK).callbackCount()).isZero();
        assertThat(ScenarioSetting.of(PaymentScenario.DUPLICATE_CALLBACK).callbackCount()).isEqualTo(2);
    }

    @DisplayName("생성 전 확정 거절은 거래를 만들지 않고, 응답 유실은 거래를 만든 뒤 응답을 늦춘다.")
    @Test
    void separatesRejectionFromDroppedResponse() {
        ScenarioSetting rejected = ScenarioSetting.of(PaymentScenario.REJECT_BEFORE_CREATE);
        ScenarioSetting dropped = ScenarioSetting.of(PaymentScenario.DROP_RESPONSE);

        assertThat(rejected.createsTransaction()).isFalse();
        assertThat(dropped.createsTransaction()).isTrue();
        assertThat(dropped.dropsResponse()).isTrue();
        assertThat(rejected.dropsResponse()).isFalse();
    }

    @DisplayName("응답 대체 시나리오는 지정한 상태·원문 본문과 거래 생성 여부를 그대로 쓴다.")
    @Test
    void keepsOverriddenResponse() {
        ScenarioSetting withoutTransaction = ScenarioSetting.override(422, "", false);
        ScenarioSetting withTransaction = ScenarioSetting.override(202, "{\"broken\"", true);

        assertThat(withoutTransaction.scenario()).isEqualTo(PaymentScenario.CREATE_RESPONSE_OVERRIDE);
        assertThat(withoutTransaction.responseStatus()).isEqualTo(422);
        assertThat(withoutTransaction.responseBody()).isEmpty();
        assertThat(withoutTransaction.createsTransaction()).isFalse();
        assertThat(withTransaction.createsTransaction()).isTrue();
        assertThat(withTransaction.responseBody()).isEqualTo("{\"broken\"");
    }

    @DisplayName("응답 대체 시나리오는 올바른 HTTP 상태와 본문이 있어야 한다.")
    @ParameterizedTest
    @ValueSource(ints = {99, 600})
    void rejectsInvalidOverrideStatus(int status) {
        assertThatIllegalArgumentException().isThrownBy(() -> ScenarioSetting.override(status, "", false));
        assertThatIllegalArgumentException().isThrownBy(() -> ScenarioSetting.override(422, null, false));
    }

    @DisplayName("응답 대체 시나리오는 of로 만들지 않고 override로만 만든다.")
    @Test
    void requiresOverrideFactoryForResponseOverride() {
        assertThatIllegalArgumentException().isThrownBy(() -> ScenarioSetting.of(PaymentScenario.CREATE_RESPONSE_OVERRIDE));
        assertThatIllegalArgumentException().isThrownBy(() -> ScenarioSetting.of(null));
    }
}
