package com.loopers.infrastructure.order;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주간 랭킹 실제 연결 테스트가 띄우는 batch 자식 JVM 한 번의 실행(랭킹 설계 11.5). 끝날 때까지 기다려 종료 코드와 출력을 돌려준다.
 * classpath 는 Gradle 이 넘긴 batch 제품 runtime + 테스트 진입점 패키지뿐이며, 접속값은 부모 fixture 의 실제 값을 명시한다.
 * 띄운 프로세스는 모두 기록해 두고, 정리 단계는 {@link #stopRemaining}·{@link #allExited()} 로 실제 종료를 확인한다.
 */
final class WeeklyRankingBatchChild {

    private static final String MAIN = "com.loopers.job.ranking.process.WeeklyRankingTestProcessMain";
    private static final Pattern RESULT = Pattern.compile(
        "weeklyRanking 결과 instanceId=(\\d+) executionId=(\\d+) .* status=([A-Z]+) publicationOutcome=(\\S+)");
    private static final Pattern PROCESSED = Pattern.compile("WEEKLY_RANKING_PROCESS productId=(\\d+)");
    private static final Pattern CHECKPOINT = Pattern.compile("WEEKLY_RANKING_CHECKPOINT executionId=\\d+ step=\\S+ readCount=(\\S+)");
    private static final List<Process> STARTED = new CopyOnWriteArrayList<>();

    record Result(int exitCode, String output) {

        long instanceId() {
            return Long.parseLong(result().group(1));
        }

        long executionId() {
            return Long.parseLong(result().group(2));
        }

        String status() {
            return result().group(3);
        }

        /** 이번 실행의 공개 Step 결과. 공개 Step 을 실행하지 않았으면 "null". */
        String publicationOutcome() {
            return result().group(4);
        }

        /** 자식이 JobExplorer 로 다시 읽은 계산 Step 의 영속 reader 위치. */
        String readCount() {
            Matcher matcher = CHECKPOINT.matcher(output);
            assertThat(matcher.find()).as("batch 자식의 checkpoint 로그").isTrue();
            return matcher.group(1);
        }

        List<Long> processedProductIds() {
            List<Long> ids = new ArrayList<>();
            Matcher matcher = PROCESSED.matcher(output);
            while (matcher.find()) {
                ids.add(Long.parseLong(matcher.group(1)));
            }
            return ids;
        }

        private Matcher result() {
            Matcher matcher = RESULT.matcher(output);
            assertThat(matcher.find()).as("batch 자식의 결과 로그").isTrue();
            return matcher;
        }
    }

    private WeeklyRankingBatchChild() {}

    /** 다섯 랭킹 테이블·Batch metadata 를 비파괴로 준비한다. Job 은 선택·실행하지 않는다. */
    static Result prepareSchema(Path logDir, Duration limit) throws Exception {
        return run(logDir, "prepare", limit, List.of("--spring.batch.job.name=NONE",
            "--spring.jpa.hibernate.ddl-auto=update", "--spring.batch.jdbc.initialize-schema=always"));
    }

    static Result startNew(Path logDir, String periodStart, Long failBeforeProductId, Duration limit) throws Exception {
        List<String> args = new ArrayList<>(launchArgs());
        args.addAll(List.of("--weekly-ranking.mode=new", "--weekly-ranking.period-start=" + periodStart));
        if (failBeforeProductId != null) {
            args.add("--test.weekly-ranking.fail-before-product-id=" + failBeforeProductId);
        }
        return run(logDir, "new-" + periodStart, limit, args);
    }

    static Result restart(Path logDir, long executionId, Duration limit) throws Exception {
        List<String> args = new ArrayList<>(launchArgs());
        args.addAll(List.of("--weekly-ranking.mode=restart", "--weekly-ranking.execution-id=" + executionId));
        return run(logDir, "restart-" + executionId, limit, args);
    }

    /** 이미 준비된 DB 를 보존하는 명시 launch 설정(랭킹 설계 4.7). */
    private static List<String> launchArgs() {
        return List.of("--spring.batch.job.name=weeklyRanking", "--spring.jpa.hibernate.ddl-auto=none",
            "--spring.batch.jdbc.initialize-schema=never", "--weekly-ranking.launch.enabled=true");
    }

    private static Result run(Path logDir, String name, Duration limit, List<String> specific) throws Exception {
        String classpath = System.getProperty("weekly-ranking.batch-child.classpath", "");
        assertThat(classpath).as("batch 자식 classpath(:apps:commerce-api:test 가 넘김)").isNotBlank();
        List<String> args = new ArrayList<>(List.of(
            "--spring.profiles.active=test",
            "--spring.batch.job.enabled=false",
            "--datasource.mysql-jpa.main.jdbc-url=" + System.getProperty("datasource.mysql-jpa.main.jdbc-url"),
            "--datasource.mysql-jpa.main.username=" + System.getProperty("datasource.mysql-jpa.main.username"),
            "--datasource.redis.database=" + System.getProperty("datasource.redis.database"),
            "--datasource.redis.master.host=" + System.getProperty("datasource.redis.master.host"),
            "--datasource.redis.master.port=" + System.getProperty("datasource.redis.master.port"),
            "--datasource.redis.replicas[0].host=" + System.getProperty("datasource.redis.replicas[0].host"),
            "--datasource.redis.replicas[0].port=" + System.getProperty("datasource.redis.replicas[0].port")));
        args.addAll(specific);
        assertThat(args).as("부모 fixture 접속값이 모두 있어야 한다").noneMatch(arg -> arg.endsWith("=null"));

        Files.createDirectories(logDir);
        Path log = logDir.resolve("batch-" + name + "-" + System.nanoTime() + ".log");
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath, MAIN));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("DATASOURCE_MYSQLJPA_MAIN_PASSWORD", System.getProperty("datasource.mysql-jpa.main.password"));
        System.out.println("[WEEKLY_RANKING_FLOW BATCH CHILD] " + name + " log=" + log.toAbsolutePath() + " args=" + specific
            + " env=[DATASOURCE_MYSQLJPA_MAIN_PASSWORD=(hidden)]");

        Process process = builder.start();
        STARTED.add(process);
        try {
            if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                boolean stopped = process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                throw new AssertionError("batch 자식 " + name + " 이 " + limit + " 안에 끝나지 않아 강제 종료를 요청했습니다. [종료 확인="
                    + stopped + "]\n" + tail(log));
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw interrupted;
        }
        Result result = new Result(process.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        System.out.println("[WEEKLY_RANKING_FLOW BATCH CHILD] " + name + " exit=" + result.exitCode());
        return result;
    }

    /** 아직 살아 있는 batch 자식을 강제 종료하고 기다린다. 끝나지 않으면 실패로 알린다. */
    static void stopRemaining(Duration limit) throws InterruptedException {
        for (Process process : STARTED) {
            if (process.isAlive() && !process.destroyForcibly().waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("batch 자식 pid=" + process.pid() + " 이 종료되지 않았습니다.");
            }
        }
    }

    static boolean allExited() {
        return STARTED.stream().noneMatch(Process::isAlive);
    }

    private static String tail(Path log) throws IOException {
        String text = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "";
        return text.length() <= 4_000 ? text : text.substring(text.length() - 4_000);
    }
}
