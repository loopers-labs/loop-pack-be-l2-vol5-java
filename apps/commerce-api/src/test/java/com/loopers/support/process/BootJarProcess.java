package com.loopers.support.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 다른 앱의 bootJar 를 별도 JVM 프로세스로 띄우고 출력 줄을 모음. 운영과 같은 실행물로 앱 사이를 확인할 때 씀 (7주차 설계 10장).
 * 대기에는 모두 deadline 을 두고, 프로세스가 먼저 죽으면 마지막 출력을 붙여 실패시킴
 */
public final class BootJarProcess implements AutoCloseable {

    private static final int TAIL_LINES = 40;

    private final String name;
    private final Process process;
    private final List<String> lines = new CopyOnWriteArrayList<>();

    private BootJarProcess(String name, Process process) {
        this.name = name;
        this.process = process;
        Thread reader = new Thread(this::collect, name + "-output");
        reader.setDaemon(true);
        reader.start();
    }

    public static BootJarProcess start(String name, Path bootJar, List<String> args) {
        if (!Files.isRegularFile(bootJar)) {
            throw new IllegalStateException(name + " 실행물이 없음: " + bootJar);
        }
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            // 출력 줄을 UTF-8 로 읽으므로 OS 기본 인코딩에 맡기지 않음
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Duser.timezone=" + System.getProperty("user.timezone"),
            "-jar",
            bootJar.toString()
        ));
        command.addAll(args);
        try {
            return new BootJarProcess(name, new ProcessBuilder(command).redirectErrorStream(true).start());
        } catch (IOException e) {
            throw new IllegalStateException(name + " 프로세스를 띄우지 못함", e);
        }
    }

    private void collect() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                System.out.println("[" + name + "] " + line);
            }
        } catch (IOException ignored) {
            // 프로세스를 끝내면 stream 이 닫힘
        }
    }

    public void awaitLine(Predicate<String> condition, Duration timeout) {
        awaitCount(condition, 1, timeout);
    }

    public void awaitCount(Predicate<String> condition, long expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (count(condition) < expected) {
            if (!process.isAlive()) {
                throw new AssertionError(name + " 프로세스가 먼저 끝남. exit=" + process.exitValue() + "\n" + tail());
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError(name + " 에서 기대한 줄 " + expected + "개를 " + timeout + " 안에 보지 못함. 본 것=" + count(condition) + "\n" + tail());
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("대기 중 interrupt", e);
            }
        }
    }

    public long count(Predicate<String> condition) {
        return lines.stream().filter(condition).count();
    }

    public String tail() {
        List<String> snapshot = List.copyOf(lines);
        return String.join("\n", snapshot.subList(Math.max(0, snapshot.size() - TAIL_LINES), snapshot.size()));
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
