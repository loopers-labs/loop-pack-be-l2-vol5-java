package com.loopers.application.event;

import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.event.DomainEventPublisher;
import com.loopers.domain.order.OrderConfirmedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Real Spring event synchronization without a DB. Real SQL rollback is covered by the order integration test. */
@ExtendWith(OutputCaptureExtension.class)
class DomainEventListenerTest {
    private final OrderConfirmedEvent order = new OrderConfirmedEvent("OrderConfirmed", 1,
        "order-confirmed:v1:42", 42L, Instant.parse("2026-10-29T00:00:00.123456Z"),
        List.of(new OrderConfirmedEvent.Item(7L, 2)));
    private final ProbeEvent probe = new ProbeEvent("probe:1", "probe-key");
    private AnnotationConfigApplicationContext context;
    private DomainEventPublisher publisher;
    private RecordingTransactionManager manager;
    private TransactionTemplate transaction;

    @BeforeEach
    void openContext() {
        context = new AnnotationConfigApplicationContext(TestConfiguration.class);
        publisher = context.getBean(DomainEventPublisher.class);
        manager = context.getBean(RecordingTransactionManager.class);
        transaction = new TransactionTemplate(manager);
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void oneListenerForwardsDifferentEventTypesOnlyAfterCommit() {
        doAnswer(invocation -> {
            assertThat(manager.commits).isEqualTo(1);
            return null;
        }).when(publisher).publish(any(DomainEvent.class));

        transaction.executeWithoutResult(status -> {
            context.publishEvent(order);
            context.publishEvent(probe);
            verifyNoInteractions(publisher);
        });

        assertThat(context.getBeansOfType(DomainEventListener.class)).hasSize(1);
        verify(publisher).publish(order);
        verify(publisher).publish(probe);
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void rollbackSuppressesBothTypes() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            context.publishEvent(order);
            context.publishEvent(probe);
            throw new IllegalStateException("injected rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(manager.commits).isZero();
        assertThat(manager.rollbacks).isEqualTo(1);
        verifyNoInteractions(publisher);
    }

    @Test
    void rollbackOnlyWithoutAnExceptionAlsoSuppressesPublication() {
        transaction.executeWithoutResult(status -> {
            context.publishEvent(probe);
            status.setRollbackOnly();
        });

        assertThat(manager.rollbacks).isEqualTo(1);
        verifyNoInteractions(publisher);
    }

    @Test
    void eventsWithoutATransactionDoNotBypassTheCommitBoundary() {
        context.publishEvent(order);
        context.publishEvent(probe);

        verifyNoInteractions(publisher);
    }

    @Test
    void ordinaryLocalEventsAreNotForwardedEvenWhenCommitted() {
        transaction.executeWithoutResult(status -> context.publishEvent(new LocalOnlyEvent("internal detail")));

        assertThat(manager.commits).isEqualTo(1);
        verifyNoInteractions(publisher);
    }

    @Test
    void publicationFailureDoesNotEscapeOrPreventTheNextCommittedEvent(CapturedOutput output) {
        doThrow(new IllegalStateException("private-payload")).when(publisher).publish(order);

        assertThatCode(() -> transaction.executeWithoutResult(status -> {
            context.publishEvent(order);
            context.publishEvent(probe);
        })).doesNotThrowAnyException();

        assertThat(manager.commits).isEqualTo(1);
        assertThat(manager.rollbacks).isZero();
        verify(publisher).publish(order);
        verify(publisher).publish(probe);
        verifyNoMoreInteractions(publisher);
        assertThat(output.getAll()).contains("domain_event_dispatch_failed", "eventType=OrderConfirmed",
            "eventId=order-confirmed:v1:42", "key=42", "errorType=IllegalStateException")
            .doesNotContain("private-payload");
    }

    record ProbeEvent(String eventId, String key) implements DomainEvent {
        @Override
        public String eventType() {
            return "TestProbe";
        }
    }

    record LocalOnlyEvent(String detail) { }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(DomainEventListener.class)
    static class TestConfiguration {
        @Bean
        DomainEventPublisher domainEventPublisher() {
            return mock(DomainEventPublisher.class);
        }

        @Bean
        RecordingTransactionManager transactionManager() {
            return new RecordingTransactionManager();
        }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks++;
        }
    }
}
