package com.loopers.support.test;

import static org.awaitility.Awaitility.await;

import com.loopers.support.preservation.PreservedMySql;
import com.loopers.testcontainers.KafkaTestFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.RetriableException;
import org.testcontainers.kafka.KafkaContainer;

/** 전용 Kafka와 주문·구매 활동·주간 랭킹을 저장하는 실험용 Commerce DB. */
public final class PurchaseActivityLab implements AutoCloseable {
    private static final String TOPIC = "order-events";
    private static final String GROUP = "purchase-activity";
    private final KafkaContainer kafka = KafkaTestFixture.create();
    private PreservedMySql database;
    private Process streamer;

    private PurchaseActivityLab() {}

    public static PurchaseActivityLab start(Path runDirectory) {
        String jar = Objects.requireNonNull(System.getProperty("purchase.streamer.jar"), "commerce-streamer bootJar 경로가 필요합니다.");
        Path log = runDirectory.toAbsolutePath().resolve("streamer.log");
        var lab = new PurchaseActivityLab();
        try {
            Files.createDirectories(log.getParent());
            lab.database = new PreservedMySql();
            lab.database.prepareCommerceSchema();
            try (var connection = lab.connect()) {
                WeeklyRankingBatchLab.prepare(connection);
            }
            lab.kafka.start();
            try (var admin = Admin.create(Map.of("bootstrap.servers", lab.bootstrapServers()))) {
                admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(20, TimeUnit.SECONDS);
                lab.streamer = lab.launch(jar, log);
                await().alias("구매 활동 소비자의 두 파티션 배정 · " + log)
                    .atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofMillis(500))
                    .failFast("구매 활동 streamer가 종료되었습니다. " + log, () -> !lab.streamer.isAlive())
                    .until(() -> hasBothPartitions(admin));
            }
            return lab;
        } catch (Exception failure) {
            try {
                lab.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            throw new IllegalStateException("구매 활동 실험 환경을 기동하지 못했습니다. 로그 " + log, failure);
        }
    }

    private Process launch(String jar, Path log) throws IOException {
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", binary).toString(),
            "-XX:ActiveProcessorCount=2", "-Xmx256m", "-Duser.timezone=Asia/Seoul", "-Dfile.encoding=UTF-8", "-jar", jar,
            "--spring.profiles.active=local", "--spring.main.web-application-type=none",
            "--spring.jpa.hibernate.ddl-auto=none", "--spring.jpa.show-sql=false",
            "--datasource.mysql-jpa.main.jdbc-url=" + database.jdbcUrl(),
            "--datasource.mysql-jpa.main.minimum-idle=0", "--datasource.mysql-jpa.main.maximum-pool-size=3",
            "--spring.kafka.bootstrap-servers=" + bootstrapServers(), "--order-events.topic=" + TOPIC, "--order-events.group=" + GROUP)
            .directory(log.getParent().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static boolean hasBothPartitions(Admin admin) throws Exception {
        try {
            var group = admin.describeConsumerGroups(List.of(GROUP), new DescribeConsumerGroupsOptions().timeoutMs(3_000))
                .all().get(5, TimeUnit.SECONDS).get(GROUP);
            var assigned = group.members().stream().flatMap(member -> member.assignment().topicPartitions().stream()).collect(Collectors.toSet());
            return assigned.equals(Set.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1)));
        } catch (TimeoutException starting) {
            return false;
        } catch (ExecutionException starting) {
            if (starting.getCause() instanceof GroupIdNotFoundException || starting.getCause() instanceof RetriableException) { return false; }
            throw starting;
        }
    }

    public String bootstrapServers() { return kafka.getBootstrapServers(); }
    public String topic() { return TOPIC; }
    public String group() { return GROUP; }
    public String jdbcUrl() { return database.jdbcUrl(); }
    public Connection connect() throws SQLException { return database.connect(); }

    @Override
    public void close() {
        try {
            if (streamer != null && streamer.isAlive()) {
                streamer.destroy();
                if (!streamer.waitFor(10, TimeUnit.SECONDS)) {
                    streamer.destroyForcibly();
                    if (!streamer.waitFor(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("구매 활동 streamer를 종료하지 못했습니다. pid=" + streamer.pid());
                    }
                }
            }
        } catch (InterruptedException failure) {
            streamer.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("구매 활동 streamer 종료 대기가 중단됐습니다.", failure);
        } finally {
            try {
                kafka.close();
            } finally {
                if (database != null) { database.close(); }
            }
        }
    }
}
