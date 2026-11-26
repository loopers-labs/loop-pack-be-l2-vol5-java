package com.loopers.support.incident;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// 이 실행이 띄운 앱 JAR 자식 프로세스다. 정리는 이 실행이 띄운 프로세스만 한다.
// local 프로필 기본값이 공유 로컬 인프라를 가리키므로, 주소 인자가 이 실행의 Testcontainers 주소가 아니면 띄우기 전에 멈춘다.
public final class ChildApp implements AutoCloseable {
    private static final Set<Process> RUNNING = ConcurrentHashMap.newKeySet();
    private static final Set<String> SHARED_HOST_PORTS = Set.of(":3306", ":19092", ":6379", ":6380");

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> RUNNING.forEach(Process::destroyForcibly)));
    }

    private final Process process;

    private ChildApp(Process process) {
        this.process = process;
    }

    public static ChildApp start(String name, Path jar, List<String> arguments, Path log) {
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xms64m", "-Xmx384m", "-jar", jar.toString()));
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            RUNNING.add(process);
            return new ChildApp(process);
        } catch (IOException exception) {
            throw new IllegalStateException(name + " 프로세스를 시작하지 못했습니다.", exception);
        }
    }

    public static int runToCompletion(String name, Path jar, List<String> arguments, Path log, Duration timeout) {
        try (ChildApp app = start(name, jar, arguments, log)) {
            if (!app.process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(name + " 실행이 " + timeout + " 안에 끝나지 않았습니다.");
            }
            return app.process.exitValue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(name + " 실행을 기다리다 중단됐습니다.", exception);
        }
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } finally {
            RUNNING.remove(process);
        }
    }

    public record Infra(String jdbcUrl, String dbUsername, String dbPassword, String kafkaBootstrap,
                        String redisHost, int redisPort) {

        public static Infra fromTestContainers() {
            return new Infra(required("datasource.mysql-jpa.main.jdbc-url"), required("datasource.mysql-jpa.main.username"),
                required("datasource.mysql-jpa.main.password"), required("spring.kafka.bootstrap-servers"),
                required("datasource.redis.master.host"), Integer.parseInt(required("datasource.redis.master.port")));
        }

        private static String required(String key) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Testcontainers 주소가 없습니다: " + key);
            }
            return value;
        }
    }

    public static List<String> streamerArguments(Infra infra, String topic, String groupId) {
        List<String> arguments = new ArrayList<>(common(infra));
        arguments.addAll(List.of(
            "--spring.kafka.bootstrap-servers=" + infra.kafkaBootstrap(),
            "--spring.kafka.admin.properties.bootstrap.servers=" + infra.kafkaBootstrap(),
            "--order.confirmed-event.topic=" + topic,
            "--order.confirmed-event.group-id=" + groupId,
            "--server.port=0",
            "--server.address=127.0.0.1",
            "--management.server.port=0",
            "--management.server.address=127.0.0.1"
        ));
        verify(arguments, infra, true);
        return arguments;
    }

    public static List<String> weeklyRankingArguments(Infra infra, LocalDate periodStart, int revision) {
        List<String> arguments = new ArrayList<>(common(infra));
        arguments.addAll(List.of("--job.name=weeklyRanking", "periodStart=" + periodStart, "revision=" + revision));
        verify(arguments, infra, false);
        return arguments;
    }

    private static List<String> common(Infra infra) {
        return List.of(
            "--spring.profiles.active=local",
            "--datasource.mysql-jpa.main.jdbc-url=" + infra.jdbcUrl(),
            "--datasource.mysql-jpa.main.username=" + infra.dbUsername(),
            "--datasource.mysql-jpa.main.password=" + infra.dbPassword(),
            "--datasource.mysql-jpa.main.maximum-pool-size=5",
            "--datasource.mysql-jpa.main.minimum-idle=1",
            "--datasource.redis.master.host=" + infra.redisHost(),
            "--datasource.redis.master.port=" + infra.redisPort(),
            "--datasource.redis.replicas[0].host=" + infra.redisHost(),
            "--datasource.redis.replicas[0].port=" + infra.redisPort()
        );
    }

    static void verify(List<String> arguments, Infra infra, boolean usesKafka) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("--datasource.mysql-jpa.main.jdbc-url", infra.jdbcUrl());
        expected.put("--datasource.redis.master.host", infra.redisHost());
        expected.put("--datasource.redis.master.port", String.valueOf(infra.redisPort()));
        expected.put("--datasource.redis.replicas[0].host", infra.redisHost());
        expected.put("--datasource.redis.replicas[0].port", String.valueOf(infra.redisPort()));
        if (usesKafka) {
            expected.put("--spring.kafka.bootstrap-servers", infra.kafkaBootstrap());
            expected.put("--spring.kafka.admin.properties.bootstrap.servers", infra.kafkaBootstrap());
        }
        expected.forEach((key, value) -> {
            List<String> found = arguments.stream().filter(argument -> argument.startsWith(key + "=")).toList();
            if (found.size() != 1 || !found.getFirst().equals(key + "=" + value)) {
                throw new IllegalStateException("자식 프로세스 주소 인자가 이 실행의 Testcontainers 값이 아닙니다: " + key + " " + found);
            }
        });
        List<String> addresses = List.of(infra.jdbcUrl(), infra.kafkaBootstrap(), infra.redisHost() + ":" + infra.redisPort());
        for (String address : addresses) {
            if (address.contains("kafka:9092") || SHARED_HOST_PORTS.stream().anyMatch(port -> address.contains(port + "/")
                || address.endsWith(port))) {
                throw new IllegalStateException("공유 로컬 인프라 주소로는 자식 프로세스를 띄우지 않습니다: " + address);
            }
        }
    }
}
