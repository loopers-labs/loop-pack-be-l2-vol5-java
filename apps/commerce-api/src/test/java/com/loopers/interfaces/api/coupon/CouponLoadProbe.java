package com.loopers.interfaces.api.coupon;

import com.loopers.support.RequestSqlProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Attributes HTTP-path JDBC checkouts to individual coupon POSTs. Worker and unrelated queries only affect pool metrics. */
final class CouponLoadProbe extends OncePerRequestFilter {
    private final ThreadLocal<Submission> current = new ThreadLocal<>();
    private volatile Sample active;

    record Requests(long requests, long requestsUsingDb, long connections, long statements) { }
    record Window(Map<Integer, Requests> couponByStatus, long poolAcquisitions, long poolWaitP95Micros,
                  long poolWaitMaxMicros, long poolTimeouts) { }

    void startWindow() {
        if (active != null) {
            throw new IllegalStateException("Measurement already in progress");
        }
        var sample = new Sample();
        sample.sql.start();
        active = sample;
    }

    Window finishWindow() {
        var sample = active;
        active = null;
        // Clients can already have their response while the servlet filter is recording its final status.
        int phase = sample.submissions.arriveAndDeregister();
        try {
            sample.submissions.awaitAdvanceInterruptibly(phase, 5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for HTTP observations", interrupted);
        } catch (TimeoutException stalled) {
            throw new IllegalStateException("Coupon HTTP observations did not finish", stalled);
        }
        var statementCounts = new HashMap<Integer, Long>();
        for (var event : sample.sql.stop()) {
            var status = sample.accessedStatuses.get(event.requestId());
            if (status == null) {
                throw new IllegalStateException("Missing HTTP result for SQL observation " + event.requestId());
            }
            statementCounts.merge(status, 1L, Long::sum);
        }
        var byStatus = new HashMap<Integer, Requests>();
        sample.completed.forEach((status, counts) -> byStatus.put(status, new Requests(counts.responses.sum(),
            counts.usedDb.sum(), counts.connections.sum(), statementCounts.getOrDefault(status, 0L))));
        List<Long> waits = sample.acquireNanos.stream().sorted().toList();
        long p95 = waits.isEmpty() ? 0 : waits.get((int) Math.ceil(waits.size() * .95) - 1);
        long max = waits.isEmpty() ? 0 : waits.getLast();
        return new Window(Map.copyOf(byStatus), waits.size(), TimeUnit.NANOSECONDS.toMicros(p95),
            TimeUnit.NANOSECONDS.toMicros(max), sample.timeouts.sum());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || !request.getRequestURI().startsWith("/api/v1/coupon-campaigns/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var sample = active;
        if (sample == null) {
            chain.doFilter(request, response);
            return;
        }
        var submission = new Submission(sample, Long.toString(sample.sequence.incrementAndGet()));
        sample.submissions.register();
        current.set(submission);
        try {
            chain.doFilter(request, response);
        } finally {
            current.remove();
            int status = response.getStatus();
            Counts counts = sample.completed.computeIfAbsent(status, unused -> new Counts());
            counts.responses.increment();
            if (submission.connections > 0) {
                counts.usedDb.increment();
                counts.connections.add(submission.connections);
                sample.accessedStatuses.put(submission.id, status);
            }
            sample.submissions.arriveAndDeregister();
        }
    }

    DataSource observe(DataSource pool) {
        return new DelegatingDataSource(pool) {
            @Override
            public Connection getConnection() throws SQLException {
                return checkout(pool::getConnection);
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return checkout(() -> pool.getConnection(username, password));
            }
        };
    }

    private Connection checkout(Borrow borrow) throws SQLException {
        Submission request = current.get();
        Sample sample = request == null ? active : request.sample;
        if (sample == null) {
            return borrow.get();
        }
        if (request != null) {
            request.connections++;
        }
        long start = System.nanoTime();
        Connection connection;
        try {
            connection = borrow.get();
        } catch (SQLTransientConnectionException timeout) {
            sample.timeouts.increment();
            throw timeout;
        } finally {
            sample.acquireNanos.add(System.nanoTime() - start);
        }
        return request == null ? connection : sample.sql.wrap(connection);
    }

    @FunctionalInterface
    private interface Borrow {
        Connection get() throws SQLException;
    }

    private final class Sample {
        private final Phaser submissions = new Phaser(1);
        private final AtomicLong sequence = new AtomicLong();
        private final Map<Integer, Counts> completed = new ConcurrentHashMap<>();
        private final Map<String, Integer> accessedStatuses = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Long> acquireNanos = new ConcurrentLinkedQueue<>();
        private final LongAdder timeouts = new LongAdder();
        private final RequestSqlProbe sql = new RequestSqlProbe(() -> current.get().id);
    }

    private static final class Counts {
        private final LongAdder responses = new LongAdder();
        private final LongAdder usedDb = new LongAdder();
        private final LongAdder connections = new LongAdder();
    }

    private static final class Submission {
        private final Sample sample;
        private final String id;
        private int connections;

        Submission(Sample sample, String id) {
            this.sample = sample;
            this.id = id;
        }
    }
}
