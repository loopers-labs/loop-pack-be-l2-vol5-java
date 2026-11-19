package com.loopers.application.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.loopers.support.preservation.PreservedMySql;
import fixture.ranking.RankingChildObservation;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

final class RankingProcessLab {
    private static final String BATCH_JAR = "ranking.worker.test-jar";
    private static final String OBSERVER = "fixture/ranking/RankingChildObservation.class";
    private static final String REPLAY = "fixture/ranking/CompletedWorkerReplay.class";
    private static final Pattern TOMCAT_PORT = Pattern.compile("Tomcat started on port (\\d+)");
    private final PreservedMySql database;
    private final Path logs;
    private final Path childClasspath;
    final JdbcTemplate jdbc;

    RankingProcessLab(Path logs, PreservedMySql database) throws IOException {
        this.database = database;
        this.logs = logs;
        Files.createDirectories(logs);
        this.childClasspath = logs.resolve("observer");
        for (String entry : List.of(OBSERVER, REPLAY)) {
            Path target = childClasspath.resolve(entry);
            Files.createDirectories(target.getParent());
            try (var source = RankingChildObservation.class.getResourceAsStream("/" + entry)) {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path factories = childClasspath.resolve("META-INF/spring.factories");
        Files.createDirectories(factories.getParent());
        Files.writeString(factories, "org.springframework.context.ApplicationContextInitializer="
            + RankingChildObservation.class.getName() + ",fixture.ranking.CompletedWorkerReplay" + System.lineSeparator(), StandardCharsets.UTF_8);
        this.jdbc = new JdbcTemplate(new DriverManagerDataSource(database.jdbcUrl(), "root", "application"));
    }

    static void prepare(PreservedMySql database) throws SQLException {
        database.prepareCommerceSchema();
        try (var connection = database.connectAsAdministrator()) {
            for (String script : List.of("014-weekly-ranking-snapshots.sql", "015-weekly-ranking-scores.sql",
                "016-weekly-ranking-publication.sql", "017-weekly-ranking-plans.sql", "018-weekly-ranking-partial-scores.sql",
                "001-batch-metadata-mysql.sql")) {
                ScriptUtils.executeSqlScript(connection, new FileSystemResource(Path.of(System.getProperty("ranking.flow.schema-directory"), script)));
            }
        }
    }

    App resident(String node, int workers, int poolSize, String... fixtureOptions) throws IOException {
        var properties = new ArrayList<>(List.of("-Dranking.lab.metrics=" + logs.resolve(node + ".port")));
        properties.addAll(List.of(fixtureOptions));
        return batch(node, properties, poolSize, List.of("--ranking.scheduler.node-id=" + node,
            "--ranking.scheduler.auto-plan=false", "--ranking.scheduler.local-grid=" + workers, "--ranking.weekly.local-workers=" + workers,
            "--ranking.weekly.chunk-size=16", "--ranking.scheduler.poll-delay=PT0.2S",
            "--ranking.scheduler.heartbeat-delay=PT1S", "--ranking.scheduler.member-timeout=PT10S"));
    }

    App request(LocalDate week, int revision, int grid) throws IOException {
        return request(week, revision, grid, false);
    }

    App measuredRequest(LocalDate week, int revision, int grid) throws IOException {
        return request(week, revision, grid, true);
    }

    private App request(LocalDate week, int revision, int grid, boolean measure) throws IOException {
        String name = "request-" + revision;
        String close = week.plusWeeks(1).atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(60).toString();
        var properties = new ArrayList<>(List.of("-Dranking.lab.time=" + close, "-Dranking.lab.start=" + logs.resolve(name + ".start")));
        if (measure) {
            properties.add("-Dranking.lab.measurement-gate=" + logs.resolve(name + ".go"));
        }
        return batch(name, properties, 8, List.of("--ranking.scheduler.mode=request",
            "--ranking.scheduler.local-grid=" + grid, "--ranking.scheduler.request-period=" + week,
            "--ranking.scheduler.request-revision=" + revision));
    }

    App recover(String snapshot, String owner, long generation, long execution) throws IOException {
        return batch("recover-" + execution, List.of(), 8, List.of("--ranking.scheduler.mode=recover",
            "--ranking.scheduler.snapshot-id=" + snapshot, "--ranking.scheduler.order-partition-id=0",
            "--ranking.scheduler.owner-id=" + owner, "--ranking.scheduler.generation=" + generation,
            "--ranking.scheduler.execution-id=" + execution, "--ranking.scheduler.termination-confirmed=true",
            "--ranking.scheduler.reason=observed-terminated-test-process"));
    }

    private App batch(String name, List<String> properties, int poolSize, List<String> options) throws IOException {
        var command = baseCommand("UTC");
        command.addAll(properties);
        command.add("-Dloader.path=" + childClasspath);
        command.addAll(List.of("-cp", System.getProperty(BATCH_JAR), "org.springframework.boot.loader.launch.PropertiesLauncher"));
        var args = new ArrayList<>(List.of(database.arguments()));
        args.removeIf(property -> property.startsWith("--datasource.mysql-jpa.main.maximum-pool-size="));
        command.addAll(args);
        command.addAll(List.of("--spring.profiles.active=ranking-preserve,ranking-scheduler",
            "--spring.batch.job.enabled=false",
            "--datasource.mysql-jpa.main.maximum-pool-size=" + poolSize, "--REDIS_MASTER_HOST=127.0.0.1",
            "--REDIS_MASTER_PORT=6379", "--REDIS_REPLICA_1_HOST=127.0.0.1", "--REDIS_REPLICA_1_PORT=6379"));
        command.addAll(options);
        return launch(logs, name, command);
    }

    long[] pool(String node) throws Exception {
        Path file = logs.resolve(node + ".port");
        await().atMost(Duration.ofSeconds(75)).until(() -> Files.exists(file) && Files.size(file) > 0);
        var uri = URI.create("http://127.0.0.1:" + Files.readString(file) + "/pool");
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("Hikari acquire timer for %s", node).isEqualTo(200);
            String[] fields = response.body().split(",");
            return new long[]{Long.parseLong(fields[0]), Long.parseLong(fields[1])};
        }
    }

    void awaitMeasurementStart(int revision, App request) {
        Path ready = logs.resolve("request-" + revision + ".go.ready");
        await().atMost(Duration.ofSeconds(90)).failFast(() -> {
            if (!request.process().isAlive()) {
                throw new AssertionError(Files.readString(request.log()));
            }
        }).until(() -> Files.exists(ready));
    }

    void startMeasurement(int revision) throws IOException {
        Files.writeString(logs.resolve("request-" + revision + ".go"), "run");
    }

    String requestStarted(int revision) throws IOException {
        return Files.readString(logs.resolve("request-" + revision + ".start"));
    }

    void awaitMembers(int count, App... residents) {
        await().atMost(Duration.ofSeconds(90)).failFast(() -> {
            for (var app : residents) {
                if (app != null && !app.process().isAlive()) {
                    throw new AssertionError(Files.readString(app.log()));
                }
            }
        }).untilAsserted(() -> assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM weekly_ranking_members WHERE heartbeat_at>=UTC_TIMESTAMP(6)-INTERVAL 10 SECOND", Integer.class))
            .isEqualTo(count));
    }

    String awaitPublication(LocalDate week, int revision, App... requests) {
        return await().pollInterval(Duration.ofMillis(50)).atMost(Duration.ofSeconds(120)).failFast(() -> {
            for (var request : requests) {
                if (!request.process().isAlive() && request.process().exitValue() != 0) {
                    throw new AssertionError(Files.readString(request.log()));
                }
            }
        }).until(() -> jdbc.queryForList("""
            SELECT snapshot_id FROM weekly_ranking_publications WHERE period_start=? AND revision=?
            """, String.class, week, revision).stream().findFirst().orElse(null), id -> id != null);
    }

    static App start(Path logs, String name, String jarProperty, String timezone, String... args) throws IOException {
        var command = baseCommand(timezone);
        command.addAll(List.of("-jar", System.getProperty(jarProperty)));
        command.addAll(List.of(args));
        return launch(logs, name, command);
    }

    private static ArrayList<String> baseCommand(String timezone) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            "-XX:ActiveProcessorCount=4", "-Xmx320m", "-Duser.timezone=" + timezone, "-Dfile.encoding=UTF-8"));
    }

    private static App launch(Path logs, String name, List<String> command) throws IOException {
        Path log = logs.resolve(name + ".log");
        var process = new ProcessBuilder(command).directory(logs.toFile()).redirectErrorStream(true)
            .redirectOutput(log.toFile()).start();
        return new App(process, log);
    }

    record App(Process process, Path log) implements AutoCloseable {
        int port(String app) throws IOException {
            await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
                String output = Files.readString(log, StandardCharsets.UTF_8);
                assertThat(process.isAlive()).withFailMessage(output).isTrue();
                assertThat(output).as(log.toString()).contains("Started " + app);
            });
            var match = TOMCAT_PORT.matcher(Files.readString(log, StandardCharsets.UTF_8));
            assertThat(match.find()).isTrue();
            return Integer.parseInt(match.group(1));
        }

        void awaitSuccess() throws Exception {
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("operator finished, log %s", log).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
        }

        @Override
        public void close() throws InterruptedException {
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("stopped JVM %s", process.pid()).isTrue();
                }
            }
        }
    }
}
