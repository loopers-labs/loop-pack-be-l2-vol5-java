package com.loopers.support.kafka;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Starts only the streamer's production classpath; the caller owns readiness and the isolated broker/DB. */
public final class OwnedStreamerProcess implements AutoCloseable {
    private final Process process;
    private final Path logPath;
    private final String password;

    private OwnedStreamerProcess(Process process, Path logPath, String password) {
        this.process = process;
        this.logPath = logPath;
        this.password = password;
    }

    public static OwnedStreamerProcess start(String jdbcUrl, String username, String password, String bootstrap,
                                               String topic, String group, Path logPath) throws IOException {
        String classpath = requireValue(System.getProperty("w6.streamer.main-classpath"), "streamer main classpath");
        requireValue(jdbcUrl, "owned JDBC URL");
        requireValue(username, "owned JDBC username");
        requireValue(password, "owned JDBC password");
        requireValue(bootstrap, "owned broker bootstrap");
        requireValue(topic, "owned topic");
        requireValue(group, "owned group");
        Path output = Objects.requireNonNull(logPath, "logPath").toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms128m", "-Xmx384m", "-Xshare:off", "-Duser.timezone=Asia/Seoul",
            "-cp", classpath, "com.loopers.CommerceStreamerApplication",
            "--spring.profiles.active=test",
            "--spring.main.web-application-type=none",
            "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.show-sql=false",
            "--spring.lifecycle.timeout-per-shutdown-phase=5s",
            "--datasource.mysql-jpa.main.jdbc-url=" + jdbcUrl,
            "--datasource.mysql-jpa.main.username=" + username,
            "--datasource.mysql-jpa.main.password=" + password,
            "--datasource.mysql-jpa.main.minimum-idle=1",
            "--datasource.mysql-jpa.main.maximum-pool-size=5",
            "--spring.kafka.bootstrap-servers=" + bootstrap,
            "--spring.kafka.admin.properties.bootstrap.servers=" + bootstrap,
            "--commerce.purchase-activity.topic=" + topic,
            "--commerce.purchase-activity.group-id=" + group,
            "--commerce.purchase-activity.auto-startup=true",
            "--demo-kafka.test.enabled=false",
            "--datasource.redis.database=0",
            "--datasource.redis.master.host=127.0.0.1",
            "--datasource.redis.master.port=1",
            "--datasource.redis.replicas[0].host=127.0.0.1",
            "--datasource.redis.replicas[0].port=1"));
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        OwnedStreamerProcess owned = new OwnedStreamerProcess(child, output, password);
        try {
            child.getOutputStream().close();
            return owned;
        } catch (IOException failure) {
            try {
                owned.close();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public void assertAlive() {
        if (!process.isAlive()) {
            throw new IllegalStateException("Owned streamer exited with code " + process.exitValue()
                + "; log=" + logPath + "\n" + diagnosticTail());
        }
    }

    public String log() throws IOException {
        return Files.readString(logPath, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        boolean[] interrupted = {Thread.interrupted()};
        try {
            if (process.isAlive()) {
                process.destroy(); // SIGTERM permits Spring's shutdown hook to stop its listener and datasource.
            }
            if (!awaitExit(Duration.ofSeconds(15), interrupted)) {
                process.destroyForcibly();
                if (!awaitExit(Duration.ofSeconds(5), interrupted)) {
                    throw new IllegalStateException("Owned streamer did not terminate; log=" + logPath);
                }
            }
            if (process.isAlive()) {
                throw new IllegalStateException("Owned streamer is still alive; log=" + logPath);
            }
        } finally {
            if (interrupted[0]) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private boolean awaitExit(Duration timeout, boolean[] interrupted) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (process.isAlive()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            try {
                return process.waitFor(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException cancellation) {
                // Reap the owned child even after a test timeout, then restore the caller's interrupt flag.
                interrupted[0] = true;
            }
        }
        return true;
    }

    private String diagnosticTail() {
        try {
            String sanitized = log().replace(password, "[REDACTED]");
            return sanitized.substring(Math.max(0, sanitized.length() - 8_000));
        } catch (IOException unavailable) {
            return "Streamer log could not be read";
        }
    }

    private static String requireValue(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing " + label);
        }
        return value;
    }
}
