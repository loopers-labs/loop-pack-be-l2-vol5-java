package com.loopers.application.order;

import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** listener 의 호출 순서와 commit 뒤 오류 처리를 대역으로 확인한다. TX 단계 연결은 통합 테스트가 맡는다. */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("주문 확정 listener 는 probe 뒤 포트를 한 번 부르고, RuntimeException 은 안전 로그로만 남기며 Error 는 숨기지 않는다.")
class OrderConfirmedEventListenerTest {

    private static final OrderConfirmed EVENT = new OrderConfirmed(42L,
        ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 0, ZoneOffset.UTC), List.of(new OrderConfirmed.Item(7L, 1L)));

    private final OrderPublicationProbe probe = mock(OrderPublicationProbe.class);
    private final OrderEventPublisher publisher = mock(OrderEventPublisher.class);
    private final OrderConfirmedEventListener listener = new OrderConfirmedEventListener(probe, publisher, "order-events");

    @DisplayName("probe 를 먼저 부른 뒤 포트를 한 번 부른다.")
    @Test
    void callsProbeThenPortOnce() {
        listener.on(EVENT);

        InOrder order = inOrder(probe, publisher);
        order.verify(probe).beforeSend(EVENT);
        order.verify(publisher).publish(EVENT);
        order.verifyNoMoreInteractions();
    }

    @DisplayName("probe 가 실패하면 포트를 부르지 않고 LISTENER_CALL 로그를 남긴 뒤 정상 반환한다.")
    @Test
    void probeFailureSkipsPort(CapturedOutput output) {
        doThrow(new IllegalStateException("민감한-원문")).when(probe).beforeSend(EVENT);

        assertThatCode(() -> listener.on(EVENT)).doesNotThrowAnyException();

        verify(publisher, never()).publish(EVENT);
        assertAll(
            () -> assertThat(output.getOut()).contains("ORDER_CONFIRMED_SEND_FAILURE eventId=order-confirmed:42 orderId=42 "
                + "topic=order-events stage=LISTENER_CALL exceptionType=java.lang.IllegalStateException causeType=none"),
            () -> assertThat(output.getAll()).doesNotContain("민감한-원문")
        );
    }

    @DisplayName("치명적인 Error 는 잡지 않고 그대로 전파한다.")
    @Test
    void errorPropagates(CapturedOutput output) {
        doThrow(new AssertionError("치명적")).when(publisher).publish(EVENT);

        assertThatThrownBy(() -> listener.on(EVENT)).isInstanceOf(AssertionError.class);
        assertThat(output.getOut()).doesNotContain("ORDER_CONFIRMED_SEND_FAILURE");
    }
}
