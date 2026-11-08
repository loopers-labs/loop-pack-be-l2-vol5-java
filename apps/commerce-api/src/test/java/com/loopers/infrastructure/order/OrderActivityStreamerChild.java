package com.loopers.infrastructure.order;

import com.loopers.testsupport.LocalKafkaTestSupport;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 이벤트 종단 검증이 띄우는 실제 streamer bootJar 자식 JVM 하나의 수명(C10 에서 추출). 기동 인자·로그 파일·기동 완료·listener 배정·
 * 정상 종료 확인만 맡는다. 어떤 topic/group 을 언제 쓰고 무엇을 관찰할지는 호출하는 테스트가 정한다. API runtime 에 streamer 를 넣지 않는다.
 */
final class OrderActivityStreamerChild {

    private final LocalKafkaTestSupport kafka;
    private final String topic;
    private final String group;
    private final Path log;
    private final Process process;

    private OrderActivityStreamerChild(LocalKafkaTestSupport kafka, String topic, String group, Path log, Process process) {
        this.kafka = kafka;
        this.topic = topic;
        this.group = group;
        this.log = log;
        this.process = process;
    }

    /**
     * streamer bootJar 를 자식 JVM 으로 띄운다. 접속값은 부모 fixture 의 실제 값을 명시적으로 넘기고 개발 기본값으로 가지 않게 한다.
     * DB 비밀번호는 명령줄 대신 환경 변수로 넘겨 명령 출력에 남기지 않는다. 출력은 파일로 받아 파이프가 막히지 않게 한다.
     * 자식은 test 프로필·같은 DB/Redis/Kafka·자기 두 Entity 의 ddl-auto=create·demo OFF 로 뜬다.
     */
    static OrderActivityStreamerChild start(LocalKafkaTestSupport kafka, String topic, String group, String label) throws IOException {
        Path jar = Path.of(System.getProperty("c10.streamer.boot-jar", ""));
        assertThat(Files.isRegularFile(jar)).as("streamer bootJar 준비(:apps:commerce-streamer:bootJar)").isTrue();
        Path logDir = Path.of("build", label.toLowerCase(Locale.ROOT));
        Files.createDirectories(logDir);
        Path log = logDir.resolve("streamer-" + kafka.runId() + ".log");
        List<String> args = List.of(
            "--spring.profiles.active=test",
            "--server.port=0",
            "--management.server.port=0",
            "--spring.jpa.hibernate.ddl-auto=create",
            "--datasource.mysql-jpa.main.jdbc-url=" + System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            "--datasource.mysql-jpa.main.username=" + System.getProperty("datasource.mysql-jpa.main.username"),
            "--datasource.redis.database=" + System.getProperty("datasource.redis.database"),
            "--datasource.redis.master.host=" + System.getProperty("datasource.redis.master.host"),
            "--datasource.redis.master.port=" + System.getProperty("datasource.redis.master.port"),
            "--datasource.redis.replicas[0].host=" + System.getProperty("datasource.redis.replicas[0].host"),
            "--datasource.redis.replicas[0].port=" + System.getProperty("datasource.redis.replicas[0].port"),
            "--spring.kafka.bootstrap-servers=" + kafka.bootstrapServers(),
            "--spring.kafka.admin.properties.bootstrap.servers=" + kafka.bootstrapServers(),
            "--commerce.order-activity.topic=" + topic,
            "--commerce.order-activity.group-id=" + group,
            "--demo-kafka.enabled=false");
        assertThat(args).as("부모 fixture 접속값이 모두 있어야 한다").noneMatch(arg -> arg.endsWith("=null"));
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString()));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("DATASOURCE_MYSQLJPA_MAIN_PASSWORD", System.getProperty("datasource.mysql-jpa.main.password"));
        System.out.println("[" + label + " CHILD] jar=" + jar.getFileName() + " log=" + log.toAbsolutePath() + " args=" + args
            + " env=[DATASOURCE_MYSQLJPA_MAIN_PASSWORD=(hidden)]");
        return new OrderActivityStreamerChild(kafka, topic, group, log, builder.start());
    }

    /** Started 로그만 보지 않는다. 프로세스 생존·기동 완료를 본 뒤 listener 배정은 broker 에서 따로 확인한다. */
    void awaitReady(Duration limit) {
        await("streamer 자식 기동 완료", limit, () -> {
            if (!process.isAlive()) {
                throw new AssertionError("streamer 자식이 기동 중 종료됐습니다. exit=" + process.exitValue() + "\n" + tail(output()));
            }
            return output().contains("Started CommerceStreamerApplication");
        });
    }

    /** broker 가 본 group 이 STABLE 이고 자식의 listener member 하나가 업무 topic 의 두 partition 을 모두 배정받을 때까지 기다린다. */
    ConsumerGroupDescription awaitAssignment(Duration limit) {
        Set<TopicPartition> all = Set.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1));
        await("listener group 참여·partition 배정", limit, () -> {
            ConsumerGroupDescription description = kafka.describeGroup(group);
            return description.state() == ConsumerGroupState.STABLE && description.members().size() == 1
                && description.members().iterator().next().clientId().startsWith("commerce-streamer-")
                && description.members().iterator().next().assignment().topicPartitions().equals(all);
        });
        return kafka.describeGroup(group);
    }

    boolean isAlive() {
        return process.isAlive();
    }

    /**
     * SIGTERM 으로 정상 종료를 요청하고 기다린다. 시간 안에 끝나지 않을 때만 강제 종료하며 그 사실을 남긴다.
     * 기다리는 중 interrupt 되면 강제 종료를 요청하고 플래그를 복원해 다시 던진다. 실제 종료 여부는 호출자가 {@link #isAlive()} 로 본다.
     * 정상 종료면 broker 의 group member 해제까지 기다린다.
     */
    void stop(String label, Duration limit) throws InterruptedException {
        process.destroy();
        boolean exited;
        try {
            exited = process.waitFor(limit.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        System.out.println("[" + label + " CHILD STOP] graceful=" + exited + " exit=" + (process.isAlive() ? "alive" : process.exitValue())
            + " stoppedLog=" + output().contains(group + ": Consumer stopped"));
        if (!exited) {
            throw new IllegalStateException("streamer 자식이 정상 종료 deadline 안에 끝나지 않아 강제 종료했습니다. [alive=" + process.isAlive() + "]");
        }
        await("자식 종료 뒤 group member 해제", limit, () -> kafka.describeGroup(group).members().isEmpty());
    }

    String output() {
        try {
            return !Files.exists(log) ? "" : Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String tail(String text) {
        return text.length() <= 4_000 ? text : text.substring(text.length() - 4_000);
    }

    static String describe(ConsumerGroupDescription description) {
        MemberDescription member = description.members().iterator().next();
        return description.state() + " clientId=" + member.clientId() + " assignment=" + member.assignment().topicPartitions();
    }

    /** 조건을 200ms 간격으로 확인한다. 고정 sleep 으로 판정하지 않고, limit 안에 관찰되지 않으면 실패한다. */
    static void await(String what, Duration limit, BooleanSupplier condition) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(what + " 가 deadline(" + limit.toMillis() + "ms) 안에 관찰되지 않았습니다.");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
