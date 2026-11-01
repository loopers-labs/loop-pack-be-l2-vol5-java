package com.loopers.interfaces.consumer;

import com.loopers.application.purchaseactivity.PurchaseActivityHandler;
import com.loopers.application.purchaseactivity.PurchaseActivityResult;
import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode;
import com.loopers.domain.purchaseactivity.PurchaseActivityException;
import com.loopers.interfaces.consumer.OrderActivityConsumeException.Stage;
import com.loopers.interfaces.consumer.order.OrderConfirmedParser;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** C6: listener 의 parser → handler → ack 순서와 단계별 안전한 오류. handler·ack 는 대역이고 실제 TX·commit 은 broker 통합 검증에서 본다. */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("구매 활동 listener 는 handler 가 정상 반환한 뒤에만 ack 하고, 실패는 ack 없이 단계·코드만 담은 오류로 던진다.")
class OrderActivityKafkaConsumerTest {

    private static final String SECRET = "PAYLOAD-SECRET-5531";
    private static final String VALID = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:42","orderId":42,
         "confirmedAt":"2026-10-28T07:00:00.123456Z","items":[{"productId":7,"quantity":2}],"memo":"%s"}
        """.formatted(SECRET);

    private final PurchaseActivityHandler handler = mock(PurchaseActivityHandler.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final OrderActivityKafkaConsumer consumer = new OrderActivityKafkaConsumer(new OrderConfirmedParser(), handler);

    private static ConsumerRecord<String, byte[]> record(String key, String value) {
        return new ConsumerRecord<>("order-events-t", 1, 5L, key, value.getBytes(StandardCharsets.UTF_8));
    }

    private OrderActivityConsumeException failure(ConsumerRecord<String, byte[]> record) {
        OrderActivityConsumeException error = catchThrowableOfType(OrderActivityConsumeException.class,
            () -> consumer.listen(record, ack));
        assertThat(error).as("실패해야 한다").isNotNull();
        return error;
    }

    @DisplayName("정상이면 handler 반환 뒤 ack 를 한 번 호출한다.")
    @Test
    void acksAfterHandler(CapturedOutput output) {
        when(handler.consume(any())).thenReturn(PurchaseActivityResult.APPLIED);

        consumer.listen(record("42", VALID), ack);

        InOrder order = inOrder(handler, ack);
        order.verify(handler).consume(any(OrderConfirmation.class));
        order.verify(ack).acknowledge();
        order.verifyNoMoreInteractions();
        assertAll(
            () -> assertThat(output).contains("ORDER_ACTIVITY_HANDLED_AND_ACK_RETURNED topic=order-events-t partition=1 offset=5 "
                + "eventId=order-confirmed:42 result=APPLIED"),
            () -> assertThat(output).doesNotContain(SECRET)
        );
    }

    @DisplayName("parser·handler·ack 실패는 각각 PARSE·HANDLE·ACK 단계이고, 원인·payload·key·원문 메시지를 담지 않는다.")
    @Test
    void failuresByStage(CapturedOutput output) {
        OrderActivityConsumeException parse = failure(record("KEY-" + SECRET, VALID));

        doThrow(new DataIntegrityViolationException("SQL-" + SECRET)).when(handler).consume(any());
        OrderActivityConsumeException handle = failure(record("42", VALID));

        doThrow(new PurchaseActivityException(PurchaseActivityErrorCode.FACT_CONFLICT, "충돌")).when(handler).consume(any());
        OrderActivityConsumeException conflict = failure(record("42", VALID));

        doReturn(PurchaseActivityResult.ALREADY_PROCESSED).when(handler).consume(any());
        doThrow(new IllegalStateException("commit-" + SECRET)).when(ack).acknowledge();
        OrderActivityConsumeException acked = failure(record("42", VALID));

        assertAll(
            () -> assertThat(parse.getStage()).isEqualTo(Stage.PARSE),
            () -> assertThat(parse.getCode()).isEqualTo("INVALID_EVENT"),
            () -> assertThat(handle.getStage()).isEqualTo(Stage.HANDLE),
            () -> assertThat(handle.getCode()).isEqualTo("NONE"),
            () -> assertThat(handle.getMessage()).contains(DataIntegrityViolationException.class.getName()),
            () -> assertThat(conflict.getStage()).isEqualTo(Stage.HANDLE),
            () -> assertThat(conflict.getCode()).isEqualTo("FACT_CONFLICT"),
            () -> assertThat(acked.getStage()).isEqualTo(Stage.ACK),
            () -> assertThat(java.util.List.of(parse, handle, conflict, acked)).allSatisfy(error -> assertAll(
                () -> assertThat(error.getCause()).isNull(),
                () -> assertThat(error.getMessage()).doesNotContain(SECRET).contains("partition=1, offset=5"))),
            () -> assertThat(output).doesNotContain(SECRET),
            () -> assertThat(output).contains("ORDER_ACTIVITY_FAILURE topic=order-events-t partition=1 offset=5 stage=PARSE code=INVALID_EVENT")
        );
    }

    @DisplayName("parser 오류에서는 handler·ack 를 부르지 않는다.")
    @Test
    void parseFailureSkipsHandlerAndAck() {
        failure(record("42", "{\"eventType\":"));

        verify(handler, never()).consume(any());
        verify(ack, never()).acknowledge();
    }

    @DisplayName("listener 메서드·클래스에 TX·@Async 가 없다.")
    @Test
    void noTransactionOrAsync() throws NoSuchMethodException {
        Method listen = OrderActivityKafkaConsumer.class.getMethod("listen", ConsumerRecord.class, Acknowledgment.class);

        assertAll(
            () -> assertThat(listen.isAnnotationPresent(Transactional.class)).isFalse(),
            () -> assertThat(listen.isAnnotationPresent(Async.class)).isFalse(),
            () -> assertThat(OrderActivityKafkaConsumer.class.isAnnotationPresent(Transactional.class)).isFalse(),
            () -> assertThat(OrderActivityKafkaConsumer.class.isAnnotationPresent(Async.class)).isFalse()
        );
    }
}
