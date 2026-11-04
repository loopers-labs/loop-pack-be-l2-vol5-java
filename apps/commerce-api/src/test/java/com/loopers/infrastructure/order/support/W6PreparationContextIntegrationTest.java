package com.loopers.infrastructure.order.support;

import com.loopers.CommerceApiApplication;
import com.loopers.application.order.OrderConfirmedRecorder;
import com.loopers.application.payment.PaymentTransactionService;
import com.loopers.domain.order.OrderConfirmedMessageEncoder;
import com.loopers.domain.order.OrderConfirmedOutboxModel;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxSender;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.ManagedType;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * W6 준비 컨텍스트의 실제 기동(설계 W7.4-1 5단계). 테스트 컨테이너 MySQL 안에 이 테스트가 소유한 빈 스키마를 만들고, 그 스키마에서
 * CommerceApiApplication 을 W6PreparationContextInitializer 와 ddl-auto=create 로 띄운다. 조립·관리 타입·생성 테이블을 실제로 확인한 뒤
 * 스키마를 지운다. 다른 테스트의 스키마·개발 DB·broker 는 건드리지 않으며 Kafka 송신·PG 호출은 하지 않는다.
 *
 * <p>W7 전용 String sender·template·factory 는 없어야 하고 순수 encoder·verifier 만 남는다. W6 JSON producer 의 앱 YAML 유효 값은
 * 구현 03 에서 OrderEventKafkaContextTest 를 W7 로 전환하며 이 테스트로 옮겼다.
 */
@Timeout(120)
@DisplayName("[W6 보존] W6 준비 컨텍스트는 W6 recorder·listener·JSON adapter 만 조립하고 Outbox 를 관리·생성하거나 W7 전송 bean 을 두지 않는다.")
class W6PreparationContextIntegrationTest {

    private static final String SCHEMA = "w6_preparation_check";

    @Test
    void assemblesW6PathWithoutOutbox() throws Exception {
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        String originalUrl = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String password = System.getProperty("datasource.mysql-jpa.main.password");
        String server = originalUrl.substring(0, originalUrl.lastIndexOf('/'));
        try (var root = new SingleConnectionDataSource(server, "root", password, true)) {
            JdbcTemplate admin = new JdbcTemplate(root);
            admin.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            admin.execute("CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
            admin.execute("GRANT ALL ON " + SCHEMA + ".* TO 'test'@'%'");
            try {
                assertThat(tables(admin)).as("기동 전 빈 스키마").isEmpty();
                try (ConfigurableApplicationContext context = boot(server)) {
                    assertAssembly(context);
                }
                List<String> tables = tables(admin);
                System.out.println("[W6 PREP] tables=" + tables);
                assertAll(
                    () -> assertThat(tables).as("W6 관리 Entity 12개의 테이블").hasSize(12),
                    () -> assertThat(tables).doesNotContain("order_confirmed_outbox")
                );
            } finally {
                admin.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            }
        }
    }

    private static void assertAssembly(ConfigurableApplicationContext context) {
        Object recorder = context.getBean(OrderConfirmedRecorder.class);
        Object listener = context.getBean(W6OrderEventTestConfiguration.LISTENER);
        Object port = context.getBean(W6OrderEventTestConfiguration.PORT);
        KafkaTemplate<?, ?> template = (KafkaTemplate<?, ?>) context.getBean(W6OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE);
        DefaultKafkaProducerFactory<?, ?> factory =
            (DefaultKafkaProducerFactory<?, ?>) context.getBean(W6OrderEventKafkaConfig.ORDER_EVENT_PRODUCER_FACTORY);
        Set<String> managed = context.getBean(EntityManagerFactory.class).getMetamodel().getManagedTypes().stream()
            .map(ManagedType::getJavaType).map(Class::getName).collect(Collectors.toSet());
        System.out.println("[W6 PREP] managed=" + managed);

        assertAll(
            () -> assertThat(context.getBeansOfType(OrderConfirmedRecorder.class)).containsOnlyKeys(W6OrderEventTestConfiguration.RECORDER),
            () -> assertThat(AopUtils.getTargetClass(recorder)).isEqualTo(W6OrderConfirmedRecorder.class),
            () -> assertThat(ReflectionTestUtils.getField(context.getBean(PaymentTransactionService.class), "recorder"))
                .as("결제 TX 서비스가 W6 recorder 를 씀").isSameAs(recorder),
            () -> assertThat(listener).isInstanceOf(W6OrderConfirmedEventListener.class),
            () -> assertThat(context.getBeansOfType(W6OrderEventPublisher.class)).containsOnlyKeys(W6OrderEventTestConfiguration.PORT),
            () -> assertThat(ReflectionTestUtils.getField(listener, "publisher")).isSameAs(port),
            () -> assertThat(port).isInstanceOf(W6KafkaOrderEventPublisher.class),
            () -> assertThat(ReflectionTestUtils.getField(port, "template")).isSameAs(template),
            () -> assertThat(template.getProducerFactory()).isSameAs(factory),
            () -> assertThat(factory.getValueSerializer()).isInstanceOf(JsonSerializer.class),
            () -> assertThat(context.getBeansOfType(OrderConfirmedOutboxRepository.class)).as("Outbox repository 없음").isEmpty(),
            () -> assertThat(context.containsBean("orderConfirmedOutboxJpaRepository")).isFalse(),
            () -> assertThat(context.containsBean("orderOutboxRecordingService")).isFalse(),
            () -> assertThat(context.getBeansOfType(OrderConfirmedMessageEncoder.class)).as("encoder 는 남지만 호출자가 없음").hasSize(1),
            () -> assertThat(context.getBeansOfType(OrderOutboxMessageVerifier.class)).as("순수 verifier 는 남음").hasSize(1),
            () -> assertThat(context.getBeansOfType(OrderOutboxSender.class)).as("W7 sender 없음").isEmpty(),
            () -> assertThat(W6PreparationContextInitializer.W7_PRODUCED_DEFINITIONS).allSatisfy(name ->
                assertThat(context.containsBean(name)).as("W7 전용 template·factory 없음: " + name).isFalse()),
            () -> assertThat(context.containsBean("orderOutboxKafkaConfig")).isFalse(),
            () -> assertThat(ReflectionTestUtils.getField(port, "topic")).isEqualTo("order-events"),
            () -> assertThat(factory.getConfigurationProperties()).as("W6 JSON producer 의 앱 YAML 유효 값")
                .doesNotContainKey(ProducerConfig.RETRIES_CONFIG)
                .doesNotContainKey(ProducerConfig.TRANSACTIONAL_ID_CONFIG)
                .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
                .containsEntry(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5")
                .containsEntry(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, "500")
                .containsEntry(ProducerConfig.RETRY_BACKOFF_MAX_MS_CONFIG, "1000")
                .containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000")
                .containsEntry(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "20000")
                .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000")
                .containsEntry(ProducerConfig.LINGER_MS_CONFIG, "0")
                .containsEntry(JsonSerializer.ADD_TYPE_INFO_HEADERS, "false"),
            () -> assertThat(factory.transactionCapable()).isFalse(),
            () -> assertThat(factory.getPhysicalCloseTimeout()).isEqualTo(Duration.ofSeconds(15)),
            () -> assertThat(factory.getKeySerializer()).isInstanceOf(StringSerializer.class),
            () -> assertThat(((JsonSerializer<?>) factory.getValueSerializer()).isAddTypeInfo()).as("type header 없음").isFalse(),
            () -> assertThat(managed).as("53f912b 관리 타입과 같음").isEqualTo(W6PreparationContextInitializer.MANAGED_TYPES),
            () -> assertThat(managed).doesNotContain(OrderConfirmedOutboxModel.class.getName())
        );
    }

    private static ConfigurableApplicationContext boot(String server) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        return new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test")
            .initializers(new W6PreparationContextInitializer())
            .run("--server.port=" + port, "--management.server.port=0", "--spring.jpa.hibernate.ddl-auto=create",
                "--datasource.mysql-jpa.main.jdbc-url=" + server + "/" + SCHEMA);
    }

    private static List<String> tables(JdbcTemplate admin) {
        return admin.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME",
            String.class, SCHEMA);
    }
}
