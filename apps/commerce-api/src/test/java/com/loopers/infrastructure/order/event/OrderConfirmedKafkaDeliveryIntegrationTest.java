package com.loopers.infrastructure.order.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.time.ApplicationTime;
import com.loopers.application.payment.ProviderOrderSuffixGenerator;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxAwaitMode;
import com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxAttempt;
import com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxRelay;
import com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxResumeRunner;
import com.loopers.infrastructure.order.outbox.OrderConfirmedOutboxStateService;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.support.time.MutableClock;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedKafkaDeliveryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedKafkaDeliveryIntegrationTest.class);
    private static final String TOPIC = "order-events-publication-" + UUID.randomUUID();
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    private static final long INITIAL_STOCK = 10L;

    @DynamicPropertySource
    static void isolatedTopic(DynamicPropertyRegistry registry) {
        registry.add("order.confirmed-event.topic", () -> TOPIC);
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MutableClock reproductionClock() {
            return new MutableClock(CONFIRMED_AT, ZoneOffset.UTC);
        }
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Autowired private KafkaAdmin kafkaAdmin;
    @Autowired private ConsumerFactory<Object, Object> consumerFactory;
    @Autowired private OrderConfirmedOutboxRelay outboxRelay;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean(name = "orderConfirmedOutboxKafkaTemplate")
    private KafkaTemplate<Object, Object> kafkaTemplate;
    @MockitoSpyBean private OrderConfirmedOutboxStateService outboxStateService;
    @MockitoBean private ProviderOrderSuffixGenerator suffixGenerator;

    @BeforeEach
    void setUp() {
        clock.set(CONFIRMED_AT);
        kafkaAdmin.setOperationTimeout(10);
    }

    @AfterEach
    void tearDown() {
        kafkaTemplate.flush();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("일시 전송 실패는 PENDING으로 남고 재시도하면 같은 저장 payload가 실제 broker에 기록된다.")
    @Test
    void retriesStoredOutboxPayloadToKafka() throws Exception {
        assertProducerContract();
        Fixture first = requestedOrder();
        Fixture second = requestedOrder();
        assertThat(TestPgControl.complete(first.providerOrderId())).isEqualTo(1);
        assertThat(reconcile(first).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(TestPgControl.complete(second.providerOrderId())).isEqualTo(1);
        assertThat(reconcile(second).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertConfirmedWithReservedStock(first);
        assertConfirmedWithReservedStock(second);
        assertThat(outbox(first.orderId()).status()).isEqualTo("PENDING");
        assertThat(outbox(second.orderId()).status()).isEqualTo("PENDING");
        Map<TopicPartition, Long> initialOffsets = logEnds();

        AtomicInteger temporaryFailures = new AtomicInteger();
        Map<String, CompletableFuture<?>> sendFutures = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(1, String.class);
            if (first.orderId().toString().equals(key) && temporaryFailures.getAndIncrement() == 0) {
                return CompletableFuture.failedFuture(
                    new org.apache.kafka.common.errors.TimeoutException("통제된 broker 전송 timeout"));
            }
            CompletableFuture<?> future = (CompletableFuture<?>) invocation.callRealMethod();
            sendFutures.put(key, future);
            return future;
        }).when(kafkaTemplate).send(eq(TOPIC), anyString(), any());

        outboxRelay.runDueBatch();
        assertThat(outbox(first.orderId())).satisfies(row -> {
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.attemptCount()).isEqualTo(1);
            assertThat(row.cycleAttemptCount()).isEqualTo(1);
            assertThat(row.lastError()).contains("TimeoutException");
        });
        assertThat(outbox(second.orderId())).satisfies(row -> {
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.attemptCount()).isZero();
        });
        assertThat(logEnds()).isEqualTo(initialOffsets);

        clock.set(CONFIRMED_AT.plusSeconds(5));
        outboxRelay.runDueBatch();

        OutboxSnapshot firstOutbox = outbox(first.orderId());
        OutboxSnapshot secondOutbox = outbox(second.orderId());
        assertThat(firstOutbox.status()).isEqualTo("PUBLISHED");
        assertThat(firstOutbox.attemptCount()).isEqualTo(2);
        assertThat(firstOutbox.cycleAttemptCount()).isEqualTo(2);
        assertThat(secondOutbox.status()).isEqualTo("PUBLISHED");
        assertThat(secondOutbox.attemptCount()).isEqualTo(1);

        RecordMetadata firstMetadata = metadata(sendFutures.get(first.orderId().toString()));
        RecordMetadata secondMetadata = metadata(sendFutures.get(second.orderId().toString()));
        assertRecord(first, firstMetadata);
        assertRecord(second, secondMetadata);
        Map<TopicPartition, Long> publishedOffsets = logEnds();
        assertThat(publishedOffsets.values().stream().mapToLong(Long::longValue).sum()
            - initialOffsets.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(2L);

        assertThat(reconcile(first).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reconcile(second).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(TestPgControl.stats(first.providerOrderId()).createRequests()).isEqualTo(1);
        assertThat(TestPgControl.stats(second.providerOrderId()).createRequests()).isEqualTo(1);
        verify(kafkaTemplate, times(2)).send(eq(TOPIC), eq(first.orderId().toString()), any());
        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq(second.orderId().toString()), any());
        logObservation("retried", first, firstMetadata);
        logObservation("other", second, secondMetadata);
    }

    @DisplayName("세 번째 전송 실패는 해당 행만 BLOCKED로 바꾸고 다음 행 발행을 계속한다.")
    @Test
    void blocksAfterThreeRetryableFailures() throws Exception {
        Fixture fixture = requestedOrder();
        assertThat(TestPgControl.complete(fixture.providerOrderId())).isEqualTo(1);
        assertThat(reconcile(fixture).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<TopicPartition, Long> initialOffsets = logEnds();
        OutboxSnapshot initial = outbox(fixture.orderId());
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger retryableFailures = new AtomicInteger(3);
        Map<String, CompletableFuture<?>> successfulSends = new ConcurrentHashMap<>();
        doAnswer(invocation -> {
            String key = invocation.getArgument(1, String.class);
            sends.incrementAndGet();
            if (fixture.orderId().toString().equals(key) && retryableFailures.getAndDecrement() > 0) {
                return CompletableFuture.failedFuture(
                    new org.apache.kafka.common.errors.TimeoutException("통제된 반복 전송 timeout"));
            }
            CompletableFuture<?> future = (CompletableFuture<?>) invocation.callRealMethod();
            successfulSends.put(key, future);
            return future;
        }).when(kafkaTemplate).send(eq(TOPIC), anyString(), any());

        Fixture following = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            clock.set(CONFIRMED_AT.plusSeconds(5L * (attempt - 1)));
            if (attempt == 3) {
                following = requestedOrder();
                assertThat(TestPgControl.complete(following.providerOrderId())).isEqualTo(1);
                assertThat(reconcile(following).getStatusCode()).isEqualTo(HttpStatus.OK);
            }
            outboxRelay.runDueBatch();
            OutboxSnapshot row = outbox(fixture.orderId());
            assertThat(row.attemptCount()).isEqualTo(attempt);
            assertThat(row.cycleAttemptCount()).isEqualTo(attempt);
            assertThat(row.status()).isEqualTo(attempt == 3 ? "BLOCKED" : "PENDING");
        }

        assertThat(following).isNotNull();
        assertThat(outbox(following.orderId())).satisfies(row -> {
            assertThat(row.status()).isEqualTo("PUBLISHED");
            assertThat(row.attemptCount()).isEqualTo(1);
        });
        RecordMetadata followingMetadata = metadata(successfulSends.get(following.orderId().toString()));
        assertRecord(following, followingMetadata, CONFIRMED_AT.plusSeconds(10));

        clock.set(CONFIRMED_AT.plusSeconds(30));
        outboxRelay.runDueBatch();

        assertThat(sends).hasValue(4);
        assertThat(logEnds().values().stream().mapToLong(Long::longValue).sum()
            - initialOffsets.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1L);
        OutboxSnapshot blocked = outbox(fixture.orderId());
        assertThat(blocked.status()).isEqualTo("BLOCKED");
        assertThat(blocked.eventId()).isEqualTo(initial.eventId());
        assertThat(blocked.messageKey()).isEqualTo(initial.messageKey());
        assertThat(blocked.payload()).isEqualTo(initial.payload());
        assertThat(meterRegistry.get("order_confirmed_outbox.pending").gauge().value()).isZero();
        assertThat(meterRegistry.get("order_confirmed_outbox.blocked").gauge().value()).isEqualTo(1D);
        assertThat(meterRegistry.get("outbox_blocked_transitions_total").tag("reason", "ATTEMPTS_EXHAUSTED")
            .counter().count()).isEqualTo(1D);

        OrderConfirmedOutboxResumeRunner resumeRunner = new OrderConfirmedOutboxResumeRunner(
            outboxStateService, new ApplicationTime(clock), outboxRelay, blocked.eventId());
        resumeRunner.run(new DefaultApplicationArguments());

        assertThat(outbox(fixture.orderId())).satisfies(row -> {
            assertThat(row.status()).isEqualTo("PUBLISHED");
            assertThat(row.attemptCount()).isEqualTo(4);
            assertThat(row.cycleAttemptCount()).isEqualTo(1);
            assertThat(row.eventId()).isEqualTo(initial.eventId());
            assertThat(row.messageKey()).isEqualTo(initial.messageKey());
            assertThat(row.payload()).isEqualTo(initial.payload());
        });
        assertThat(sends).hasValue(5);
        assertThat(logEnds().values().stream().mapToLong(Long::longValue).sum()
            - initialOffsets.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(2L);
        outboxRelay.runDueBatch();
        assertThat(meterRegistry.get("order_confirmed_outbox.pending").gauge().value()).isZero();
        assertThat(meterRegistry.get("order_confirmed_outbox.blocked").gauge().value()).isZero();
    }

    @DisplayName("broker 기록 뒤 PUBLISHED 저장이 실패하면 재시작 시 같은 메시지를 다시 발행한다.")
    @Test
    void republishesSameMessageWhenPublishedStateCommitFails() throws Exception {
        Fixture fixture = requestedOrder();
        assertThat(TestPgControl.complete(fixture.providerOrderId())).isEqualTo(1);
        assertThat(reconcile(fixture).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<TopicPartition, Long> initialOffsets = logEnds();
        AtomicInteger stateFailures = new AtomicInteger(1);
        List<CompletableFuture<?>> sends = new ArrayList<>();

        doAnswer(invocation -> {
            CompletableFuture<?> future = (CompletableFuture<?>) invocation.callRealMethod();
            sends.add(future);
            return future;
        }).when(kafkaTemplate).send(eq(TOPIC), eq(fixture.orderId().toString()), any());
        doAnswer(invocation -> {
            if (stateFailures.getAndDecrement() > 0) {
                throw new org.springframework.dao.DataAccessResourceFailureException("통제된 PUBLISHED 저장 실패");
            }
            return invocation.callRealMethod();
        }).when(outboxStateService).markPublished(any(), any(ZonedDateTime.class));

        outboxRelay.runDueBatch();

        RecordMetadata firstMetadata = metadata(sends.getFirst());
        assertThat(outbox(fixture.orderId())).satisfies(row -> {
            assertThat(row.status()).isEqualTo("PENDING");
            assertThat(row.attemptCount()).isEqualTo(1);
            assertThat(row.lastError()).isNull();
        });
        assertRecord(fixture, firstMetadata);
        assertThat(logEnds()).containsEntry(new TopicPartition(TOPIC, firstMetadata.partition()), firstMetadata.offset() + 1);

        clock.set(CONFIRMED_AT.plusSeconds(7));
        outboxRelay.runDueBatch();

        RecordMetadata secondMetadata = metadata(sends.getLast());
        assertThat(outbox(fixture.orderId())).satisfies(row -> {
            assertThat(row.status()).isEqualTo("PUBLISHED");
            assertThat(row.attemptCount()).isEqualTo(2);
            assertThat(row.cycleAttemptCount()).isEqualTo(2);
        });
        assertRecord(fixture, secondMetadata);
        assertThat(secondMetadata.offset()).isGreaterThan(firstMetadata.offset());
        assertThat(secondMetadata.partition()).isEqualTo(firstMetadata.partition());
        assertThat(sends).hasSize(2);
        assertThat(logEnds().values().stream().mapToLong(Long::longValue).sum()
            - initialOffsets.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(2L);
        logObservation("published-state-retry-first", fixture, firstMetadata);
        logObservation("published-state-retry-second", fixture, secondMetadata);
    }

    @DisplayName("제한 시간 대기와 callback은 같은 broker 결과를 PUBLISHED로 반영한다.")
    @Test
    @SuppressWarnings("unchecked")
    void comparesBoundedWaitAndCallbackCompletion() throws Exception {
        Fixture boundedWaitFixture = requestedOrder();
        assertThat(TestPgControl.complete(boundedWaitFixture.providerOrderId())).isEqualTo(1);
        assertThat(reconcile(boundedWaitFixture).getStatusCode()).isEqualTo(HttpStatus.OK);

        AtomicInteger sendCount = new AtomicInteger();
        List<CompletableFuture<?>> actualSends = new ArrayList<>();
        AtomicReference<CompletableFuture<SendResult<Object, Object>>> heldAcknowledgement = new AtomicReference<>();
        CompletableFuture<SendResult<Object, Object>> deferred = new CompletableFuture<>();
        doAnswer(invocation -> {
            CompletableFuture<?> actual = (CompletableFuture<?>) invocation.callRealMethod();
            actualSends.add(actual);
            if (sendCount.getAndIncrement() == 1) {
                heldAcknowledgement.set((CompletableFuture<SendResult<Object, Object>>) actual);
                return deferred;
            }
            return actual;
        }).when(kafkaTemplate).send(eq(TOPIC), anyString(), any());

        assertThat(outboxRelay.runDueBatch(OrderConfirmedOutboxAwaitMode.BOUNDED_WAIT)).isTrue();
        RecordMetadata boundedMetadata = metadata(actualSends.getFirst());
        assertThat(outbox(boundedWaitFixture.orderId()).status()).isEqualTo("PUBLISHED");
        assertRecord(boundedWaitFixture, boundedMetadata);

        Fixture callbackFixture = requestedOrder();
        assertThat(TestPgControl.complete(callbackFixture.providerOrderId())).isEqualTo(1);
        assertThat(reconcile(callbackFixture).getStatusCode()).isEqualTo(HttpStatus.OK);
        String callbackEventId = "order-confirmed:v1:" + callbackFixture.orderId();
        AtomicReference<String> callbackStateThreadName = new AtomicReference<>();
        doAnswer(invocation -> {
            OrderConfirmedOutboxAttempt attempt = invocation.getArgument(0);
            if (callbackEventId.equals(attempt.eventId())) {
                callbackStateThreadName.set(Thread.currentThread().getName());
            }
            return invocation.callRealMethod();
        }).when(outboxStateService).markPublished(any(), any(ZonedDateTime.class));

        assertThat(outboxRelay.runDueBatch(OrderConfirmedOutboxAwaitMode.CALLBACK)).isTrue();
        assertThat(outbox(callbackFixture.orderId()).status()).isEqualTo("PENDING");
        assertThat(outboxRelay.runDueBatch(OrderConfirmedOutboxAwaitMode.CALLBACK)).isFalse();

        SendResult<Object, Object> callbackResult = heldAcknowledgement.get().get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
        deferred.complete(callbackResult);
        awaitOutboxStatus(callbackFixture.orderId(), "PUBLISHED");
        RecordMetadata callbackMetadata = metadata(deferred);
        assertThat(outbox(callbackFixture.orderId()).status()).isEqualTo("PUBLISHED");
        assertThat(callbackStateThreadName.get()).startsWith("order-confirmed-outbox-callback");
        assertRecord(callbackFixture, callbackMetadata);

        log.info("OUTBOX_AWAIT_COMPARISON boundedWait=ack-observed-before-return callback=returned-before-ack "
                + "topic={} boundedPartition={} boundedOffset={} callbackPartition={} callbackOffset={}",
            TOPIC, boundedMetadata.partition(), boundedMetadata.offset(),
            callbackMetadata.partition(), callbackMetadata.offset());
    }

    @DisplayName("로컬·테스트의 초기화는 없는 topic을 partition 3개·복제 1개로 만든다.")
    @Test
    void createsMissingTopic() throws Exception {
        String topic = "order-events-create-" + UUID.randomUUID();

        new OrderConfirmedKafkaConfig().orderEventsTopicInitializer(kafkaAdmin, topic)
            .run(new DefaultApplicationArguments());

        var partitions = kafkaAdmin.describeTopics(topic).get(topic).partitions();
        assertThat(partitions).hasSize(3);
        assertThat(partitions).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
    }

    @DisplayName("이미 존재하는 topic을 초기화해도 partition 수를 자동으로 늘리지 않는다.")
    @Test
    void preservesExistingPartitions() throws Exception {
        String topic = "order-events-existing-" + UUID.randomUUID();
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(topic).partitions(1).replicas(1).build());

        new OrderConfirmedKafkaConfig().orderEventsTopicInitializer(kafkaAdmin, topic)
            .run(new DefaultApplicationArguments());

        assertThat(kafkaAdmin.describeTopics(topic).get(topic).partitions()).hasSize(1);
    }

    private Fixture requestedOrder() throws Exception {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("publication-" + UUID.randomUUID()));
        Product first = Product.create(brand.getId(), "첫 상품", 3_500L);
        Product second = Product.create(brand.getId(), "둘째 상품", 4_800L);
        first.changeStockTo(INITIAL_STOCK);
        second.changeStockTo(INITIAL_STOCK);
        first = productRepository.save(first);
        second = productRepository.save(second);
        String body = """
            {"items":[{"productId":%d,"quantity":2},{"productId":%d,"quantity":1}]}
            """.formatted(first.getId(), second.getId());
        ResponseEntity<String> created = request("/api/v1/orders", user.getId(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long orderId = objectMapper.readTree(created.getBody()).path("data").path("id").asLong();
        assertThat(orderId).isPositive();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenReturn(suffix);
        String providerOrderId = "LP-ORD-%010d-%s".formatted(orderId, suffix);
        TestPgControl.scenario(providerOrderId, "{\"scenario\":\"SKIP_CALLBACK\"}");
        ResponseEntity<String> confirmed = request("/api/v1/orders/" + orderId + "/confirm", user.getId(), """
            {"paymentAttemptKey":"%s","cardType":"TEST","cardNo":"0000"}
            """.formatted(UUID.randomUUID()));
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(orderId).orElseThrow();
        assertThat(attempt.getProviderOrderId()).isEqualTo(providerOrderId);
        return new Fixture(user.getId(), orderId, attempt.getProviderOrderId(), List.of(first.getId(), second.getId()));
    }

    private ResponseEntity<String> reconcile(Fixture fixture) {
        return request("/api/v1/orders/" + fixture.orderId() + "/payment/reconcile", fixture.userId(), null);
    }

    private ResponseEntity<String> request(String path, Long userId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private void assertConfirmedWithReservedStock(Fixture fixture) {
        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        assertThat(productRepository.findById(fixture.productIds().get(0)).orElseThrow().getStock().amount()).isEqualTo(8L);
        assertThat(productRepository.findById(fixture.productIds().get(1)).orElseThrow().getStock().amount()).isEqualTo(9L);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).createRequests()).isEqualTo(1);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).transactions()).isEqualTo(1);
    }

    private void assertProducerContract() {
        ProducerConfig config = new ProducerConfig(kafkaTemplate.getProducerFactory().getConfigurationProperties());
        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(config.getInt(ProducerConfig.RETRIES_CONFIG)).isPositive();
        assertThat(config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)).isLessThanOrEqualTo(5);
        assertThat(config.originals().get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG))
            .isEqualTo(org.apache.kafka.common.serialization.StringSerializer.class);
        assertThat(config.originals().get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG))
            .isEqualTo(org.apache.kafka.common.serialization.StringSerializer.class);
        assertThat(config.getInt(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG)).isEqualTo(2_000);
        assertThat(config.getInt(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isEqualTo(5_000);
        assertThat(config.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(1_000L);
    }

    private RecordMetadata metadata(CompletableFuture<?> future) throws Exception {
        assertThat(future).isNotNull();
        Object completed = future.get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertThat(completed).isInstanceOf(SendResult.class);
        return ((SendResult<?, ?>) completed).getRecordMetadata();
    }

    private void assertRecord(Fixture fixture, RecordMetadata metadata) throws Exception {
        assertRecord(fixture, metadata, CONFIRMED_AT);
    }

    private void assertRecord(Fixture fixture, RecordMetadata metadata, Instant confirmedAt) throws Exception {
        ConsumerRecord<Object, Object> record = readRecord(metadata);
        assertThat(record.topic()).isEqualTo(metadata.topic()).isEqualTo(TOPIC);
        assertThat(record.partition()).isEqualTo(metadata.partition());
        assertThat(record.offset()).isEqualTo(metadata.offset());
        assertThat(record.key()).isEqualTo(fixture.orderId().toString());
        assertThat(record.value()).isInstanceOf(byte[].class);
        JsonNode actual = objectMapper.readTree((byte[]) record.value());
        JsonNode expected = objectMapper.readTree("""
            {
              "eventType": "OrderConfirmed", "schemaVersion": 1,
              "eventId": "order-confirmed:v1:%d", "orderId": %d,
              "confirmedAt": "%s",
              "items": [{"productId": %d, "quantity": 2}, {"productId": %d, "quantity": 1}]
            }
            """.formatted(fixture.orderId(), fixture.orderId(), confirmedAt,
            fixture.productIds().get(0), fixture.productIds().get(1)));
        assertThat(actual).isEqualTo(expected);
    }

    private OutboxSnapshot outbox(Long orderId) {
        return jdbcTemplate.queryForObject("""
            SELECT event_id, message_key, payload, status, attempt_count, cycle_attempt_count, last_error
            FROM order_confirmed_outbox
            WHERE order_id = ?
            """, (resultSet, rowNum) -> new OutboxSnapshot(
            resultSet.getString("event_id"), resultSet.getString("message_key"), resultSet.getString("payload"),
            resultSet.getString("status"), resultSet.getInt("attempt_count"),
            resultSet.getInt("cycle_attempt_count"), resultSet.getString("last_error")), orderId);
    }

    private void awaitOutboxStatus(Long orderId, String expectedStatus) throws InterruptedException {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            if (expectedStatus.equals(outbox(orderId).status())) {
                return;
            }
            Thread.sleep(25L);
        }
        assertThat(outbox(orderId).status()).isEqualTo(expectedStatus);
    }

    private Map<TopicPartition, Long> logEnds() {
        Consumer<Object, Object> consumer = consumerFactory.createConsumer("publication-observe-" + UUID.randomUUID(), null);
        try {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC, DEADLINE).stream()
                .map(partition -> new TopicPartition(TOPIC, partition.partition())).toList();
            return consumer.endOffsets(partitions, DEADLINE);
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    private ConsumerRecord<Object, Object> readRecord(RecordMetadata metadata) {
        Consumer<Object, Object> consumer = consumerFactory.createConsumer("publication-read-" + UUID.randomUUID(), null);
        try {
            TopicPartition partition = new TopicPartition(metadata.topic(), metadata.partition());
            consumer.assign(List.of(partition));
            consumer.seek(partition, metadata.offset());
            Instant until = Instant.now().plus(DEADLINE);
            List<ConsumerRecord<Object, Object>> records = new ArrayList<>();
            while (records.isEmpty() && Instant.now().isBefore(until)) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            assertThat(records).hasSize(1);
            return records.getFirst();
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    private void logObservation(String role, Fixture fixture, RecordMetadata metadata) {
        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
        List<String> items = order.getItems().stream()
            .map(item -> item.getProductId() + "x" + item.getQuantity()).toList();
        List<Long> stocks = fixture.productIds().stream()
            .map(productId -> productRepository.findById(productId).orElseThrow().getStock().amount()).toList();
        log.info("OUTBOX_RELAY role={} orderId={} providerOrderId={} transactionKey={} eventId={} confirmedAt={} "
                + "items={} orderStatus={} stock={} topic={} partition={} offset={}",
            role, order.getId(), attempt.getProviderOrderId(), attempt.getTransactionKey(), "order-confirmed:v1:" + order.getId(),
            order.getConfirmedAt().toInstant(), items, order.getStatus(), stocks, TOPIC,
            metadata == null ? "none" : metadata.partition(), metadata == null ? "none" : metadata.offset());
    }

    private record Fixture(Long userId, Long orderId, String providerOrderId, List<Long> productIds) {}
    private record OutboxSnapshot(String eventId, String messageKey, String payload, String status,
                                  int attemptCount, int cycleAttemptCount, String lastError) {}
}
