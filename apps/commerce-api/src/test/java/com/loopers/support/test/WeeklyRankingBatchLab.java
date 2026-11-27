package com.loopers.support.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 구매 활동이 커밋된 실험 DB에서 weeklyRanking Job을 한 번 실행한다. */
public final class WeeklyRankingBatchLab {
    private WeeklyRankingBatchLab() {}

    public static void prepare(Connection db) {
        for (String script : List.of("014-weekly-ranking-snapshots.sql", "015-weekly-ranking-scores.sql",
            "016-weekly-ranking-publication.sql", "001-batch-metadata-mysql.sql")) {
            ScriptUtils.executeSqlScript(db, new ClassPathResource("preservation/ranking/" + script));
        }
    }

    public static void run(Path runDirectory, String jdbcUrl, LocalDate periodStart, UUID snapshotId)
        throws IOException, InterruptedException {
        String jar = Objects.requireNonNull(System.getProperty("weekly-ranking.batch.jar"), "commerce-batch bootJar 경로가 필요합니다.");
        Path directory = Files.createDirectories(runDirectory.toAbsolutePath());
        Path log = directory.resolve("ranking-batch.log");
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        var launcher = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", binary).toString(),
            "-XX:ActiveProcessorCount=2", "-Xmx256m", "-Dfile.encoding=UTF-8", "-Duser.timezone=UTC", "-jar", jar,
            "--spring.profiles.active=ranking-preserve",
            "--spring.batch.job.name=weeklyRanking", "--spring.batch.job.enabled=true",
            "--COMMERCE_MYSQL_JDBC_URL=" + jdbcUrl, "--datasource.mysql-jpa.main.maximum-pool-size=4",
            "--datasource.mysql-jpa.main.minimum-idle=0",
            "periodStart=" + periodStart, "revision=1,java.lang.Long", "snapshotId=" + snapshotId)
            .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        launcher.environment().put("COMMERCE_MYSQL_USER", "application");
        launcher.environment().put("COMMERCE_MYSQL_PASSWORD", "application");
        Process batch = launcher.start();
        try {
            if (!batch.waitFor(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("weeklyRanking Job이 제한 시간 안에 종료되지 않았습니다. 로그 " + log);
            }
            if (batch.exitValue() != 0) {
                throw new IllegalStateException("weeklyRanking Job 종료 코드 " + batch.exitValue() + ". 로그 " + log);
            }
        } finally {
            if (batch.isAlive()) {
                batch.destroyForcibly();
                if (!batch.waitFor(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("commerce-batch 종료가 지연되고 있습니다. pid=" + batch.pid());
                }
            }
        }
    }
}
