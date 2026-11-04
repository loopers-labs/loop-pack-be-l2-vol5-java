package com.loopers.infrastructure.order.support;

import com.loopers.application.order.OrderConfirmedRecorder;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.testsupport.RecordingOrderEventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * W6 보존 listener 의 TX 단계 연결을 작은 Spring 컨텍스트로 확인한다(설계 W7.4-4). 보존 W6 recorder·listener 와 기록용 W6 포트,
 * 실제 Spring 이벤트·TransactionSynchronization 을 쓴다. DB 자원이 없는 테스트용 TX 관리자는 begin/commit/rollback 만 표시하며,
 * AFTER_COMMIT 호출 여부는 Spring 의 실제 TX 동기화가 정한다. 제품 W7 경로·broker 근거가 아니다.
 */
@DisplayName("[W6 보존] AFTER_COMMIT listener 는 실제 commit 뒤 한 번 포트를 부르고, rollback·TX 밖 등록에는 부르지 않는다.")
class W6OrderConfirmedEventListenerTransactionTest {

    private static final OrderConfirmed EVENT = new OrderConfirmed(42L,
        ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 0, ZoneOffset.UTC), List.of(new OrderConfirmed.Item(7L, 1L)));

    private AnnotationConfigApplicationContext context;
    private RecordingOrderEventPublisher port;
    private OrderConfirmedRecorder recorder; // @Transactional 프록시라 인터페이스로 받는다
    private ApplicationEventPublisher events;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
            .addFirst(new MapPropertySource("test", Map.of("commerce.order-events.topic", "order-events-tx")));
        context.register(Support.class);
        context.refresh();
        port = context.getBean(RecordingOrderEventPublisher.class);
        recorder = context.getBean(OrderConfirmedRecorder.class);
        events = context;
        transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @DisplayName("TX 안에서 W6 recorder 로 등록하면 commit 뒤 포트를 한 번 부르고, commit 전에는 부르지 않는다.")
    @Test
    void publishesOnceAfterCommit() {
        transactions.executeWithoutResult(status -> {
            recorder.record(EVENT);
            assertThat(port.published()).as("commit 전").isEmpty();
        });

        assertThat(port.published()).containsExactly(EVENT);
    }

    @DisplayName("등록 뒤 TX 가 rollback 되면 포트를 부르지 않는다.")
    @Test
    void skipsOnRollback() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            recorder.record(EVENT);
            throw new IllegalStateException("rollback 주입");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(port.published()).isEmpty();
    }

    @DisplayName("TX 밖에서 사건을 등록하면 fallbackExecution=false 이므로 실행하지 않고, W6 recorder 는 TX 없이 호출을 거절한다.")
    @Test
    void ignoresOutsideTransaction() {
        events.publishEvent(EVENT);

        assertAll(
            () -> assertThat(port.published()).isEmpty(),
            () -> assertThatThrownBy(() -> recorder.record(EVENT))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class),
            () -> assertThat(port.published()).isEmpty()
        );
    }

    @Configuration
    @EnableTransactionManagement
    static class Support {

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new MarkerTransactionManager();
        }

        @Bean
        RecordingOrderEventPublisher recordingOrderEventPublisher() {
            return new RecordingOrderEventPublisher();
        }

        @Bean
        W6OrderPublicationProbe probe() {
            return new W6OrderPublicationProbe.NoOp();
        }

        @Bean
        W6OrderConfirmedEventListener listener(W6OrderPublicationProbe probe, RecordingOrderEventPublisher publisher,
                                               org.springframework.core.env.Environment environment) {
            return new W6OrderConfirmedEventListener(probe, publisher, environment.getRequiredProperty("commerce.order-events.topic"));
        }

        @Bean
        W6OrderConfirmedRecorder recorder(ApplicationEventPublisher eventPublisher) {
            return new W6OrderConfirmedRecorder(eventPublisher);
        }
    }

    /** 자원 없이 TX 경계만 표시한다. 동기화 활성·commit/rollback 콜백은 AbstractPlatformTransactionManager 가 수행한다. */
    static final class MarkerTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        /** 바깥 TX 가 열려 있으면 참여(MANDATORY 허용), 없으면 새 TX 다. */
        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
