package fixture.ranking;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;

/** Loaded exclusively from the integration test's isolated child-JVM class path. */
public final class RankingChildObservation implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String instant = System.getProperty("ranking.lab.time");
        if (instant != null) {
            context.getBeanFactory().registerSingleton("labClosedWeekClock", Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
        }
        String start = System.getProperty("ranking.lab.start");
        if (start != null) {
            context.addApplicationListener(event -> {
                if (event instanceof ApplicationStartedEvent) {
                    String gate = System.getProperty("ranking.lab.measurement-gate");
                    if (gate != null) {
                        awaitMeasurementBaseline(Path.of(gate));
                    }
                    var jdbc = new JdbcTemplate(context.getBean("mySqlMainDataSource", DataSource.class));
                    write(Path.of(start), jdbc.queryForObject("SELECT DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%d %H:%i:%s.%f')", String.class));
                }
            });
        }
        String metrics = System.getProperty("ranking.lab.metrics");
        if (metrics != null) {
            context.addApplicationListener(event -> {
                if (event instanceof ApplicationReadyEvent) {
                    observePool(context, Path.of(metrics));
                }
            });
        }
    }

    private static void awaitMeasurementBaseline(Path gate) {
        write(Path.of(gate + ".ready"), "ready");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        try {
            while (!Files.exists(gate)) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Measurement baseline was not collected: " + gate);
                }
                Thread.sleep(10);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Measurement baseline wait interrupted", interrupted);
        }
    }

    private static void observePool(ConfigurableApplicationContext context, Path port) {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var registry = context.getBean(MeterRegistry.class);
            server.createContext("/pool", exchange -> {
                try (exchange) {
                    var acquire = registry.find("hikaricp.connections.acquire").timer();
                    if (acquire == null) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                    String response = acquire.count() + "," + (long) acquire.totalTime(TimeUnit.NANOSECONDS);
                    byte[] data = response.getBytes(StandardCharsets.US_ASCII);
                    exchange.sendResponseHeaders(200, data.length);
                    exchange.getResponseBody().write(data);
                }
            });
            server.start();
            context.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    server.stop(0);
                }
            });
            write(port, Integer.toString(server.getAddress().getPort()));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void write(Path path, String value) {
        try {
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
