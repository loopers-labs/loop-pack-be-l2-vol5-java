package com.loopers.support.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 테스트용: commerce-batch 실행 jar 로 주간 랭킹 job 을 한 번 실행하고 끝날 때까지 기다린다. 앱끼리 의존하지 않도록 별도
 * 프로세스로 띄우고, 테스트가 띄운 MySQL 주소를 넘긴다.
 *
 * <p>ddl-auto 는 update 로 덮어쓴다. test 프로필의 create 는 batch 가 읽기 전용으로 매핑한 구매 활동 테이블까지 다시 만들어
 * streamer 가 저장한 입력을 지운다.
 */
public final class BatchProcess {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private BatchProcess() {}

    /** 실행 결과. 종료 코드와 로그 끝부분을 담는다. */
    public record Result(int exitCode, String logTail) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    public static Result runWeeklyRanking(LocalDate periodStart, long revision) {
        String jar = System.getProperty("batch.jar");
        if (jar == null) {
            throw new IllegalStateException("batch.jar 시스템 속성이 없습니다. gradle e2eTest 로 실행해야 batch 실행 jar 가 준비됩니다.");
        }
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-jar", jar,
            "--spring.profiles.active=test",
            "--spring.jpa.hibernate.ddl-auto=update",
            "--job.name=weeklyRanking"
        ));
        System.getProperties().stringPropertyNames().stream()
            .filter(name -> name.startsWith("datasource."))
            .forEach(name -> command.add("--" + name + "=" + System.getProperty(name)));
        command.add("periodStart=" + periodStart + ",java.time.LocalDate");
        command.add("revision=" + revision + ",java.lang.Long");
        Path logFile = null;
        try {
            logFile = Files.createTempFile("commerce-batch-", ".log");
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(logFile.toFile())
                .start();
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("batch 가 " + TIMEOUT + " 안에 끝나지 않았습니다.\n" + tail(logFile));
            }
            return new Result(process.exitValue(), tail(logFile));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("batch 실행 대기 중 중단됨", e);
        } finally {
            deleteQuietly(logFile);
        }
    }

    private static String tail(Path logFile) {
        try {
            List<String> lines = Files.readAllLines(logFile);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(batch 로그를 읽지 못했습니다: " + e.getMessage() + ")";
        }
    }

    private static void deleteQuietly(Path logFile) {
        if (logFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(logFile);
        } catch (IOException e) {
            // 로그 파일을 지우지 못해도 프로세스는 이미 끝났다.
        }
    }
}
