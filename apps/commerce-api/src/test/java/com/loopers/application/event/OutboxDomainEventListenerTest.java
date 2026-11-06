package com.loopers.application.event;

import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.outbox.OutboxRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class OutboxDomainEventListenerTest {
    private AnnotationConfigApplicationContext context;
    private OutboxRecorder recorder;
    private RecordingTransactionManager manager;
    private TransactionTemplate tx;
    private final ProbeEvent first = new ProbeEvent("first");
    private final OtherEvent second = new OtherEvent("second");

    @BeforeEach
    void open() {
        context = new AnnotationConfigApplicationContext(Config.class);
        recorder = context.getBean(OutboxRecorder.class);
        manager = context.getBean(RecordingTransactionManager.class);
        tx = new TransactionTemplate(manager);
    }

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void oneCommonListenerRecordsBothTypesInsideTheTransactionBeforeItsCommit() {
        doAnswer(invocation -> {
            assertThat(manager.commits).isZero();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(recorder).record(any(DomainEvent.class));
        tx.executeWithoutResult(status -> {
            context.publishEvent(first);
            context.publishEvent(second);
            verifyNoInteractions(recorder);
        });
        assertThat(manager.commits).isEqualTo(1);
        assertThat(context.getBeansOfType(DomainEventListener.class)).hasSize(1);
        verify(recorder).record(first);
        verify(recorder).record(second);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void recordFailureEscapesAndRollsBackTheBusinessTransaction() {
        var failure = new IllegalStateException("injected storage failure");
        doThrow(failure).when(recorder).record(first);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> context.publishEvent(first))).isSameAs(failure);

        assertThat(manager.commits).isZero();
        assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test
    void rollbackOnlyNeverRecordsAnEvent() {
        tx.executeWithoutResult(status -> {
            context.publishEvent(first);
            status.setRollbackOnly();
        });
        verifyNoInteractions(recorder);
        assertThat(manager.rollbacks).isEqualTo(1);
    }

    @Test
    void failureBeforeCommitNeverRecordsAnEvent() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            context.publishEvent(first);
            throw new IllegalStateException("business failure");
        })).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(recorder);
    }

    @Test
    void noTransactionAndOrdinaryLocalEventsCannotBypassTheBoundary() {
        context.publishEvent(first);
        tx.executeWithoutResult(status -> context.publishEvent("ordinary local event"));
        verifyNoInteractions(recorder);
    }

    record ProbeEvent(String eventId) implements DomainEvent {
        @Override public String eventType() { return "Probe"; }
        @Override public String key() { return eventId; }
    }

    record OtherEvent(String eventId) implements DomainEvent {
        @Override public String eventType() { return "Other"; }
        @Override public String key() { return eventId; }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(DomainEventListener.class)
    static class Config {
        @Bean OutboxRecorder recorder() { return mock(OutboxRecorder.class); }
        @Bean RecordingTransactionManager transactionManager() { return new RecordingTransactionManager(); }
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        private int commits;
        private int rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(DefaultTransactionStatus status) { rollbacks++; }
    }
}
