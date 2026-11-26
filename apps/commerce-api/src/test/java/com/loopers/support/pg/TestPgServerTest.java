package com.loopers.support.pg;

import com.loopers.domain.payment.gateway.PaymentGatewayRequest;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.infrastructure.payment.client.TestPgPaymentGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 10주차 사고 실험이 기대는 fixture 제어(D-199)를 실제 adapter 로 확인함 */
class TestPgServerTest {

    private static final String DROPPED = "LP-ORD-0000000001";
    private static final String NORMAL = "LP-ORD-0000000002";
    private static final long AMOUNT = 1_000L;

    private TestPgServer pg;
    private TestPgPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        pg = TestPgServer.start();
        gateway = TestPgGateways.gateway(pg.baseUrl(), Duration.ofMillis(500), TestPgGateways.singleAttemptPolicy());
    }

    @AfterEach
    void tearDown() {
        pg.close();
    }

    @DisplayName("유실 대상 주문만 거래를 만든 뒤 응답을 잃고, 다른 주문은 그대로 생성 수락된다.")
    @Test
    void dropsOnlyTargetOrders() {
        // arrange
        pg.willDropResponseFor(Set.of(DROPPED));

        // act
        PaymentCreateResult dropped = gateway.create(request(DROPPED));
        PaymentCreateResult normal = gateway.create(request(NORMAL));

        // assert
        assertAll(
            () -> assertThat(dropped).isInstanceOf(PaymentCreateResult.Unknown.class),
            () -> assertThat(pg.transactionsOf(DROPPED)).hasSize(1),
            () -> assertThat(pg.transactionsOf(DROPPED).get(0).status()).isEqualTo("PENDING"),
            () -> assertThat(normal).isInstanceOf(PaymentCreateResult.Accepted.class),
            () -> assertThat(pg.transactionsOf(NORMAL)).hasSize(1),
            () -> assertThat(pg.createCountOf(DROPPED)).isEqualTo(1),
            () -> assertThat(pg.createCountOf(NORMAL)).isEqualTo(1)
        );
    }

    @DisplayName("유실을 끄면 대상 목록이 비고, 같은 주문의 다음 create 는 응답을 받는다.")
    @Test
    void stopsDropping() {
        // arrange
        pg.willDropResponseFor(Set.of(DROPPED));

        // act
        pg.stopDroppingResponses();
        PaymentCreateResult result = gateway.create(request(DROPPED));

        // assert
        assertAll(
            () -> assertThat(pg.dropTargets()).isEmpty(),
            () -> assertThat(result).isInstanceOf(PaymentCreateResult.Accepted.class)
        );
    }

    @DisplayName("같은 주문에 create 가 두 번 오면 거래를 두 건 만들고 중복으로 기록한다. 응답이 정상이어도 사실을 지우지 않는다.")
    @Test
    void recordsDuplicateCreates() {
        // act
        gateway.create(request(DROPPED));
        gateway.create(request(DROPPED));
        gateway.create(request(NORMAL));

        // assert
        assertAll(
            () -> assertThat(pg.transactionsOf(DROPPED)).hasSize(2),
            () -> assertThat(pg.duplicateCreates()).isEqualTo(Map.of(DROPPED, 2)),
            () -> assertThat(pg.createCount()).isEqualTo(3)
        );
    }

    @DisplayName("reset 은 유실 대상과 주문별 create 수도 비운다.")
    @Test
    void resetClearsIncidentControls() {
        // arrange
        pg.willDropResponseFor(Set.of(DROPPED));
        gateway.create(request(DROPPED));
        gateway.create(request(DROPPED));

        // act
        pg.reset();

        // assert
        assertAll(
            () -> assertThat(pg.dropTargets()).isEmpty(),
            () -> assertThat(pg.createCountOf(DROPPED)).isZero(),
            () -> assertThat(pg.duplicateCreates()).isEmpty()
        );
    }

    private static PaymentGatewayRequest request(String providerOrderId) {
        return new PaymentGatewayRequest(providerOrderId, "TEST", "0000-0000-0000-0000", AMOUNT);
    }
}
