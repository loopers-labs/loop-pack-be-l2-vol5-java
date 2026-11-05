package com.loopers.domains.order.app;

import com.loopers.domains.order.app.port.OrderConfirmedOutbox;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.app.port.OutboxMessage;
import com.loopers.domains.order.app.port.RelaySettings;
import com.loopers.domains.order.app.port.SendOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 운영 경계: relay가 실패를 기록할 때 운영자가 행을 찾을 수 있게 eventId·orderId·시도·분류·BLOCKED 여부·오류를 한 줄로 남긴다.
// 포트만 fake로 두고 relay는 실제다. 운영 로그 수집기가 이 필드를 파싱하는지는 증명하지 못한다.
@ExtendWith(OutputCaptureExtension.class)
class OrderOutboxRelayFailureLogTest {
    private static final Instant NOW = Instant.parse("2026-11-05T01:00:00Z");

    @Test
    @DisplayName("broker 수준 실패를 기록하면 eventId·orderId·시도 1·분류 BROKER·BLOCKED 아님·오류가 담긴 경고 로그 한 줄을 남긴다")
    void brokerFailureLogsFields(CapturedOutput output) {
        relay(new OutboxMessage(1, "order-confirmed:v1:41", 41, "41", "{}", 0, 0),
            new SendOutcome.BrokerFailure("TimeoutException: broker down")).runOnce();

        assertThat(output.getAll()).contains("eventId=order-confirmed:v1:41", "orderId=41", "attempt=1", "classification=BROKER",
            "blocked=false", "error=TimeoutException: broker down");
    }

    @Test
    @DisplayName("행 수준 실패가 다섯 번째라 BLOCKED로 바꾸면 eventId·orderId·시도 5·분류 ROW·BLOCKED·오류가 담긴 경고 로그 한 줄을 남긴다")
    void rowFailureThatBlocksLogsFields(CapturedOutput output) {
        relay(new OutboxMessage(2, "order-confirmed:v1:42", 42, "42", "{}", 4, 4),
            new SendOutcome.RowFailure("RecordTooLargeException: too large")).runOnce();

        assertThat(output.getAll()).contains("eventId=order-confirmed:v1:42", "orderId=42", "attempt=5", "classification=ROW",
            "blocked=true", "error=RecordTooLargeException: too large");
    }

    private static OrderOutboxRelay relay(OutboxMessage message, SendOutcome outcome) {
        return new OrderOutboxRelay(new OneRowOutbox(message), (key, payload) -> outcome,
            () -> Optional.of(new RelaySettings(100, Duration.ofSeconds(10), true)), new TransactionTemplate(new LocalTransactions()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static class OneRowOutbox implements OrderConfirmedOutbox {
        private final OutboxMessage message;
        private final AtomicBoolean returned = new AtomicBoolean();

        OneRowOutbox(OutboxMessage message) {
            this.message = message;
        }

        @Override
        public void append(OrderConfirmedPayload payload) {
        }

        @Override
        public List<OutboxMessage> findDue(Instant now, int limit) {
            if (returned.getAndSet(true)) {
                return List.of();
            }
            return List.of(message);
        }

        @Override
        public int markPublished(long id, Instant publishedAt) {
            return 1;
        }

        @Override
        public int markBrokerFailure(long id, Instant nextAttemptAt, String error) {
            return 1;
        }

        @Override
        public int markRowFailure(long id, Instant nextAttemptAt, String error, boolean blocked) {
            return 1;
        }

        @Override
        public int resumeBlocked(String eventId, Instant now) {
            return 0;
        }

        @Override
        public StoredComparison compare(OrderConfirmedPayload payload) {
            return StoredComparison.ABSENT;
        }
    }

    private static class LocalTransactions implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
