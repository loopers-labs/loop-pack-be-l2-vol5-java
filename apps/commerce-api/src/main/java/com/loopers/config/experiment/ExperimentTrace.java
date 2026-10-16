package com.loopers.config.experiment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.ArrayList;
import java.util.List;

/** Synchronous JDBC timings only; bind values and result rows are never logged. */
public final class ExperimentTrace {
    private static final ThreadLocal<ExperimentTrace> CURRENT = new ThreadLocal<>();
    private static final int MAX_QUERIES = 500;
    public final List<Query> queries = new ArrayList<>();
    public double connectionWaitMs;
    public boolean truncated;
    private final LongSupplier clock;
    private final Map<String, Timing> timings = new LinkedHashMap<>();
    private Scope activeScope;
    Scope responseScope;

    public ExperimentTrace() { this(System::nanoTime); }
    ExperimentTrace(LongSupplier clock) { this.clock = clock; }

    public record Timing(long calls, double totalMs, double selfMs) { }

    Map<String, Timing> timings() { return Map.copyOf(timings); }

    Scope scope(String name) { return new Scope(name); }

    final class Scope implements AutoCloseable {
        private final String name;
        private final Scope parent;
        private final long start;
        private long childrenNanos;
        private boolean closed;

        private Scope(String name) {
            this.name = name;
            this.parent = activeScope;
            this.start = clock.getAsLong();
            activeScope = this;
        }

        @Override public void close() {
            if (closed) return;
            long elapsed = clock.getAsLong() - start;
            Timing previous = timings.getOrDefault(name, new Timing(0, 0, 0));
            timings.put(name, new Timing(previous.calls() + 1,
                previous.totalMs() + elapsed / 1_000_000.0,
                previous.selfMs() + (elapsed - childrenNanos) / 1_000_000.0));
            if (parent != null) parent.childrenNanos += elapsed;
            activeScope = parent;
            closed = true;
        }
    }

    void finishResponse() {
        if (responseScope != null) {
            responseScope.close();
            responseScope = null;
        }
    }

    public record Query(String sql, double durationMs, boolean success, String errorType) { }

    static void begin(ExperimentTrace trace) { CURRENT.set(trace); }
    static void clear() { CURRENT.remove(); }
    static ExperimentTrace current() { return CURRENT.get(); }

    static Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                ExperimentTrace trace = CURRENT.get();
                Object result;
                try (Scope ignored = trace == null ? null : trace.scope("jdbc.connection." + method.getName())) {
                    result = call(connection, method, args);
                }
                if (result instanceof Statement statement &&
                    (method.getName().equals("prepareStatement") || method.getName().equals("prepareCall")
                        || method.getName().equals("createStatement"))) {
                    String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                    return statement(statement, sql);
                }
                return result;
            });
    }

    private static Object statement(Statement target, String preparedSql) {
        Class<?> type = target instanceof CallableStatement ? CallableStatement.class
            : target instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            ExperimentTrace trace = CURRENT.get();
            if (trace == null) return call(target, method, args);
            if (!method.getName().startsWith("execute")) {
                try (Scope ignored = trace.scope("jdbc.statement.other")) {
                    return wrapResult(call(target, method, args));
                }
            }
            String sql = preparedSql != null ? preparedSql
                : args != null && args.length > 0 && args[0] instanceof String s ? s : "<batch>";
            long start = System.nanoTime();
            Throwable failure = null;
            try (Scope ignored = trace.scope("jdbc.execute")) {
                return wrapResult(call(target, method, args));
            } catch (Throwable error) {
                failure = error;
                throw error;
            } finally {
                if (trace.queries.size() < MAX_QUERIES) {
                    trace.queries.add(new Query(sql, (System.nanoTime() - start) / 1_000_000.0,
                        failure == null, failure == null ? null : failure.getClass().getSimpleName()));
                } else {
                    trace.truncated = true;
                }
            }
        });
    }

    private static Object wrapResult(Object value) {
        if (!(value instanceof ResultSet result)) return value;
        return Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
            (proxy, method, args) -> {
                ExperimentTrace trace = CURRENT.get();
                try (Scope ignored = trace == null ? null : trace.scope("jdbc.result.read")) {
                    return call(result, method, args);
                }
            });
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
