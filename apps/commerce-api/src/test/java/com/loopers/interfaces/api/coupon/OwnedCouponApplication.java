package com.loopers.interfaces.api.coupon;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import w8.recovery.CouponRecoveryApplication;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

final class OwnedCouponApplication implements AutoCloseable {
    private final Process process;
    private final Path directory;
    private final int port;

    private OwnedCouponApplication(Process process, Path directory, int port) {
        this.process = process;
        this.directory = directory;
        this.port = port;
    }

    static OwnedCouponApplication start(MySQLContainer<?> mysql, GenericContainer<?> redis, long campaignId,
                                         boolean holdIssuance, Path runDir) throws Exception {
        return start(mysql, redis, campaignId, runDir, holdIssuance ? List.of("--hold-issuance") : List.of());
    }

    static OwnedCouponApplication startHoldingIssuanceTransaction(MySQLContainer<?> mysql, GenericContainer<?> redis,
                                                                    long campaignId, Path runDir) throws Exception {
        return start(mysql, redis, campaignId, runDir, List.of("--hold-issuance-sql"));
    }

    private static OwnedCouponApplication start(MySQLContainer<?> mysql, GenericContainer<?> redis, long campaignId,
                                                  Path runDir, List<String> testOptions) throws Exception {
        Path directory = Files.createDirectory(runDir.toAbsolutePath());
        // Load only this child entry point alongside the production API classpath.
        String entry = CouponRecoveryApplication.class.getName().replace('.', '/') + ".class";
        Path copy = directory.resolve(entry);
        Files.createDirectories(copy.getParent());
        try (var source = CouponRecoveryApplication.class.getResourceAsStream("/" + entry)) {
            Files.copy(Objects.requireNonNull(source), copy);
        }
        String mainClasspath = Objects.requireNonNull(System.getProperty("w4.startup.main-classpath"), "API runtime classpath");
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xms64m", "-Xmx288m", "-cp", directory + File.pathSeparator + mainClasspath,
            CouponRecoveryApplication.class.getName(), "--spring.config.name=coupon-recovery-isolated",
            "--server.address=127.0.0.1", "--server.port=0", "--spring.jpa.hibernate.ddl-auto=validate",
            "--spring.jpa.open-in-view=false", "--spring.sql.init.mode=never",
            "--spring.jpa.properties.hibernate.timezone.default_storage=NORMALIZE_UTC", "--spring.jpa.properties.hibernate.jdbc.time_zone=UTC",
            "--spring.datasource.hikari.minimum-idle=1", "--spring.datasource.hikari.maximum-pool-size=4",
            "--spring.data.redis.repositories.enabled=false", "--datasource.redis.database=0",
            "--datasource.redis.master.host=" + redis.getHost(), "--datasource.redis.master.port=" + redis.getMappedPort(6379),
            "--datasource.redis.replicas[0].host=" + redis.getHost(), "--datasource.redis.replicas[0].port=" + redis.getMappedPort(6379),
            "--commerce.coupon.admission.enabled=true", "--commerce.coupon.admission.campaign-ids=" + campaignId,
            "--commerce.coupon.worker.enabled=true", "--commerce.coupon.worker.campaign-ids=" + campaignId,
            "--commerce.coupon.worker.capacity=1", "--commerce.coupon.worker.poll-delay=100ms"));
        command.addAll(testOptions);
        Path log = directory.resolve("child.log");
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        var env = builder.environment();
        env.clear();
        env.put("SPRING_DATASOURCE_URL", mysql.getJdbcUrl());
        env.put("SPRING_DATASOURCE_USERNAME", mysql.getUsername());
        env.put("SPRING_DATASOURCE_PASSWORD", mysql.getPassword());
        Process process = builder.start();
        try {
            Path ready = directory.resolve("ready");
            await().atMost(Duration.ofSeconds(45)).until(() -> Files.isRegularFile(ready) || !process.isAlive());
            if (!process.isAlive()) {
                throw new IllegalStateException(Files.readString(log));
            }
            return new OwnedCouponApplication(process, directory, Integer.parseInt(Files.readString(ready)));
        } catch (Exception | AssertionError failure) {
            terminate(process);
            throw failure;
        }
    }

    URI uri(String absolutePath) {
        return URI.create("http://127.0.0.1:" + port + absolutePath);
    }

    long pid() {
        return process.pid();
    }

    boolean issuancePaused() {
        return Files.isRegularFile(directory.resolve("issuance-paused"));
    }

    boolean hasPolled() {
        return Files.isRegularFile(directory.resolve("polled"));
    }

    boolean sawWaiting(long reservationId) {
        return Files.isRegularFile(directory.resolve("first-waiting-" + reservationId));
    }

    void kill() {
        terminate(process);
    }

    @Override
    public void close() {
        terminate(process);
    }

    private static void terminate(Process child) {
        child.destroyForcibly();
        boolean interrupted = false;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        try {
            while (child.isAlive()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new IllegalStateException("Owned coupon JVM is still running: " + child.pid());
                }
                try {
                    child.waitFor(left, TimeUnit.NANOSECONDS);
                } catch (InterruptedException cancelled) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
