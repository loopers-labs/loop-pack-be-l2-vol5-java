package com.loopers.domain.pg.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgScenarioTest {
    @Test
    @DisplayName("생략한 조건과 정상 성공 프리셋은 같은 실효값이다")
    void defaultScenario() {
        assertThat(PgScenario.resolve(null, null))
                .isEqualTo(PgScenario.resolve(PgScenario.Preset.NORMAL_SUCCESS, null));
    }

    @Test
    @DisplayName("응답 유실과 거래 실패 예정은 독립적으로 저장할 수 있다")
    void lostFailure() {
        PgScenario scenario = PgScenario.resolve(PgScenario.Preset.LOST_FAILURE, null);
        assertThat(scenario.finalStatus()).isEqualTo(PgStatus.FAILED);
        assertThat(scenario.processingDelayMs()).isEqualTo(1000);
        assertThat(scenario.createResponseMode()).isEqualTo(PgScenario.ResponseMode.DROP);
        assertThat(scenario.callbackMode()).isEqualTo(PgScenario.CallbackMode.SEND);
    }

    @Test
    @DisplayName("세부 조건은 지정한 필드만 바꾸고 나머지는 프리셋을 유지한다")
    void overridesOnlySpecifiedFields() {
        PgScenario.Overrides overrides = new PgScenario.Overrides(
                null, null, 2000L, null, null, null, null, PgScenario.CallbackMode.DROP, null);
        PgScenario scenario = PgScenario.resolve(PgScenario.Preset.LOST_SUCCESS, overrides);
        assertThat(scenario.processingDelayMs()).isEqualTo(2000);
        assertThat(scenario.createResponseMode()).isEqualTo(PgScenario.ResponseMode.DROP);
        assertThat(scenario.finalStatus()).isEqualTo(PgStatus.SUCCESS);
        assertThat(scenario.callbackMode()).isEqualTo(PgScenario.CallbackMode.DROP);
    }

    @Test
    @DisplayName("처리 중 유지와 생성 전 거절에는 자동 완료나 callback을 예약하지 않는다")
    void noCompletion() {
        PgScenario pending = PgScenario.resolve(PgScenario.Preset.PENDING, null);
        PgScenario rejection = PgScenario.resolve(PgScenario.Preset.REJECT_BEFORE_CREATE, null);
        assertThat(pending.finalStatus()).isEqualTo(PgStatus.PENDING);
        assertThat(pending.callbackMode()).isEqualTo(PgScenario.CallbackMode.DROP);
        assertThat(rejection.rejectBeforeCreate()).isTrue();
        assertThat(rejection.callbackMode()).isEqualTo(PgScenario.CallbackMode.DROP);
    }

    @Test
    @DisplayName("무의미한 지연과 시간 범위 초과 및 처리 중 callback은 거절한다")
    void rejectsContradictions() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PgScenario(false, PgStatus.SUCCESS,
                0, PgScenario.ResponseMode.NORMAL, 1, PgScenario.ResponseMode.NORMAL, 0,
                PgScenario.CallbackMode.SEND, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PgScenario(false, PgStatus.SUCCESS,
                60001, PgScenario.ResponseMode.NORMAL, 0, PgScenario.ResponseMode.NORMAL, 0,
                PgScenario.CallbackMode.SEND, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PgScenario(false, PgStatus.PENDING,
                0, PgScenario.ResponseMode.NORMAL, 0, PgScenario.ResponseMode.NORMAL, 0,
                PgScenario.CallbackMode.SEND, 0));
    }
}
