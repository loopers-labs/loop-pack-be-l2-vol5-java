package com.loopers.domain.coupon;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CouponAdmissionGateTest {

    private CouponAdmissionGate gate;

    @BeforeEach
    void setUp() {
        gate = new CouponAdmissionGate();
    }

    @DisplayName("게이트는 기본적으로 닫혀 있다.")
    @Test
    void gate_defaultClosed() {
        assertThat(gate.isOpen(1L)).isFalse();
        assertThat(gate.isOpen(99L)).isFalse();
    }

    @DisplayName("open 호출 후 isOpen이 true를 반환한다.")
    @Test
    void open_setsGateOpen() {
        gate.open(1L);
        assertThat(gate.isOpen(1L)).isTrue();
    }

    @DisplayName("close 호출 후 isOpen이 false를 반환한다.")
    @Test
    void close_setsGateClosed() {
        gate.open(1L);
        gate.close(1L);
        assertThat(gate.isOpen(1L)).isFalse();
    }

    @DisplayName("forceClose는 열린 게이트를 즉시 닫는다.")
    @Test
    void forceClose_closesOpenGate() {
        gate.open(2L);
        gate.forceClose(2L);
        assertThat(gate.isOpen(2L)).isFalse();
    }

    @DisplayName("getAllOpenCampaignIds는 현재 열린 캠페인 ID만 반환한다.")
    @Test
    void getAllOpenCampaignIds_returnsOnlyOpenOnes() {
        gate.open(1L);
        gate.open(2L);
        gate.close(2L);
        gate.open(3L);

        assertThat(gate.getAllOpenCampaignIds()).containsExactlyInAnyOrder(1L, 3L);
    }

    @DisplayName("여러 캠페인의 게이트를 독립적으로 관리할 수 있다.")
    @Test
    void multiCampaign_independentGates() {
        gate.open(1L);
        assertThat(gate.isOpen(1L)).isTrue();
        assertThat(gate.isOpen(2L)).isFalse();
    }
}
