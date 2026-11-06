package com.loopers.support.streamer;

import com.loopers.testcontainers.KafkaTestContainersConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 테스트용: commerce-streamer 실행 jar 를 별도 프로세스로 띄운다. 두 앱의 component scan 이 섞이지 않게 같은 JVM 에 올리지 않는다.
 * 테스트가 띄운 MySQL·Redis·Kafka 주소와 고유 topic·group 을 넘기고, consumer 가 partition 을 배정받을 때까지 기다린다.
 */
public final class StreamerProcess implements AutoCloseable {

    private static final Duration READY_TIMEOUT = Duration.ofSeconds(60);
    private static final String READY_LOG = "partitions assigned";

    private final Process process;
    private final Path logFile;

    private StreamerProcess(Process process, Path logFile) {
        this.process = process;
        this.logFile = logFile;
    }

    /** topic 은 미리 만들어 둬야 한다. streamer 는 topic 을 자동으로 만들지 않는다. */
    public static StreamerProcess start(String topic, String consumerGroup) {
        String jar = System.getProperty("streamer.jar");
        if (jar == null) {
            throw new IllegalStateException("streamer.jar 시스템 속성이 없습니다. gradle test 로 실행해야 streamer 실행 jar 가 준비됩니다.");
        }
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", jar,
            "--spring.profiles.active=test",
            "--spring.kafka.bootstrap-servers=" + KafkaTestContainersConfig.bootstrapServers(),
            "--order-event.topic=" + topic,
            "--order-event.consumer-group=" + consumerGroup,
            "--server.port=0",
            "--management.server.port=0"
        ));
        // Testcontainers 가 시스템 속성으로 둔 MySQL·Redis 주소를 그대로 넘긴다.
        System.getProperties().stringPropertyNames().stream()
            .filter(name -> name.startsWith("datasource."))
            .forEach(name -> command.add("--" + name + "=" + System.getProperty(name)));
        try {
            Path logFile = Files.createTempFile("commerce-streamer-", ".log");
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
            StreamerProcess streamer = new StreamerProcess(process, logFile);
            streamer.awaitReady();
            return streamer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void awaitReady() {
        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("streamer 가 준비되기 전에 종료됐습니다.\n" + logTail());
            }
            if (readLog().contains(READY_LOG)) {
                return;
            }
            sleep(200);
        }
        close();
        throw new IllegalStateException("streamer 가 " + READY_TIMEOUT + " 안에 준비되지 않았습니다.\n" + logTail());
    }

    /** 실패 메시지에 붙일 streamer 로그의 마지막 40줄. */
    public String logTail() {
        List<String> lines = readLog().lines().toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
    }

    private String readLog() {
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            return "(streamer 로그를 읽지 못했습니다: " + e.getMessage() + ")";
        }
    }

    /** 자신이 띄운 프로세스와 로그 파일만 정리한다. */
    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            Files.deleteIfExists(logFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (IOException e) {
            // 로그 파일을 지우지 못해도 프로세스는 이미 정리됐다.
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
