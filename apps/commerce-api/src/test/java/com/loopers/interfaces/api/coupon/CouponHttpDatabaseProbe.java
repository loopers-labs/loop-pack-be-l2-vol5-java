package com.loopers.interfaces.api.coupon;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransactionRollbackException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Application connections only. The test observer uses its own connection to the same MySQL container. */
final class CouponHttpDatabaseProbe extends DelegatingDataSource {
    private final AtomicLong connections = new AtomicLong();
    private final AtomicReference<CommitGate> nextCommit = new AtomicReference<>();

    CouponHttpDatabaseProbe(DataSource delegate) {
        super(delegate);
    }

    long connections() {
        return connections.get();
    }

    CommitGate blockNextReservationCommit() {
        return armCommit("coupon_reservation");
    }

    CommitGate blockNextIssuanceCommit() {
        return armCommit("owned_coupon");
    }

    private CommitGate armCommit(String table) {
        var gate = new CommitGate(table);
        if (!nextCommit.compareAndSet(null, gate)) {
            throw new IllegalStateException("Another commit is already armed");
        }
        return gate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return observe(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return observe(super.getConnection(username, password));
    }

    private Connection observe(Connection delegate) {
        connections.incrementAndGet();
        // Each connection may carry several transactions; clear the write marker at every transaction boundary.
        var matchingWrite = new AtomicReference<CommitGate>();
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                var armed = nextCommit.get();
                if (armed != null && method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                    String normalized = sql.toLowerCase(Locale.ROOT).replace("`", "");
                    if (normalized.contains("insert into " + armed.table)) {
                        matchingWrite.set(armed);
                    }
                }
                if (method.getName().equals("commit")) {
                    var gate = matchingWrite.getAndSet(null);
                    if (gate != null && nextCommit.compareAndSet(gate, null)) {
                        gate.beforeCommit(delegate);
                    }
                } else if (method.getName().equals("rollback")) {
                    matchingWrite.set(null);
                }
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException wrapped) {
                    throw wrapped.getCause();
                }
            });
    }

    static final class CommitGate {
        private final String table;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean fail;
        private volatile boolean loseAcknowledgment;

        private CommitGate(String table) {
            this.table = table;
        }

        void failAndRelease() {
            fail = true;
            release.countDown();
        }

        void commitAndLoseAcknowledgment() {
            loseAcknowledgment = true;
            release.countDown();
        }

        void beforeCommit(Connection connection) throws SQLException {
            entered.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new SQLException("Timed out waiting for test to release " + table + " commit");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("Interrupted while waiting for " + table + " commit", interrupted);
            }
            if (fail) {
                connection.rollback();
                throw new SQLTransactionRollbackException("Injected failure before MySQL COMMIT", "40001");
            }
            if (loseAcknowledgment) {
                connection.commit();
                throw new SQLRecoverableException("Injected lost acknowledgement after MySQL COMMIT", "08007");
            }
        }
    }
}
