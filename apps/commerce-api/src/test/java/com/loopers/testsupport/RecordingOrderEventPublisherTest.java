package com.loopers.testsupport;

import com.loopers.domain.order.OrderConfirmed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("기록용 발행 포트")
class RecordingOrderEventPublisherTest {

    @DisplayName("기록용 포트는 호출을 기록하고, reset 은 기록과 hook 을 비운다.")
    @Test
    void recordingPortResets() {
        RecordingOrderEventPublisher port = new RecordingOrderEventPublisher();
        OrderConfirmed event = new OrderConfirmed(1L, ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 0, ZoneOffset.UTC),
            List.of(new OrderConfirmed.Item(1L, 1L)));
        port.onPublish(published -> {
            throw new IllegalStateException("hook");
        });

        assertThatThrownBy(() -> port.publish(event)).hasMessage("hook");
        List<OrderConfirmed> beforeReset = port.published();
        port.reset();
        port.publish(event);

        assertAll(
            () -> assertThat(beforeReset).containsExactly(event),
            () -> assertThat(port.published()).as("reset 뒤 hook 없음").containsExactly(event)
        );
    }
}
