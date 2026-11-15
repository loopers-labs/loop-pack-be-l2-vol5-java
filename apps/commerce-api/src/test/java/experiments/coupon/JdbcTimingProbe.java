package experiments.coupon;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/** 실험 전용 동기 요청의 JDBC 시간. SQL 시간은 잠금 대기·네트워크·실행을 포함하며 held 시간의 부분집합이다. */
public final class JdbcTimingProbe {
    private final LongSupplier nanoTime;
    private final ThreadLocal<Trace> current = new ThreadLocal<>();

    public JdbcTimingProbe() { this(System::nanoTime); }
    JdbcTimingProbe(LongSupplier nanoTime) { this.nanoTime = nanoTime; }

    public record RequestTiming(String kind, String requestId, int status, long startEpochMs, long endEpochMs,
                                long totalNanos, long acquireNanos, long heldNanos, long sqlNanos,
                                long couponUpdateNanos, long couponInsertNanos, long selectNanos,
                                long completionNanos, int connections, int statements, int acquireFailures) {}

    @FunctionalInterface
    public interface Acquisition { Connection get() throws SQLException; }

    public void begin(String kind) { begin(kind, ""); }
    public void begin(String kind, String requestId) { current.set(new Trace(kind, requestId, nanoTime.getAsLong())); }

    public RequestTiming end(int status) {
        Trace trace = current.get();
        current.remove();
        return new RequestTiming(trace.kind, trace.requestId, status, trace.startEpochMs, System.currentTimeMillis(),
            nanoTime.getAsLong() - trace.start, trace.acquire, trace.held, trace.sql,
            trace.update, trace.insert, trace.select, trace.completion,
            trace.connections, trace.statements, trace.acquireFailures);
    }

    public Connection acquire(Acquisition acquisition) throws SQLException {
        Trace trace = current.get();
        if (trace == null) return acquisition.get();
        long start = nanoTime.getAsLong();
        Connection connection;
        try {
            connection = acquisition.get();
        } catch (SQLException | RuntimeException failure) {
            trace.acquireFailures++;
            throw failure;
        } finally {
            trace.acquire += nanoTime.getAsLong() - start;
        }
        trace.connections++;
        long heldStart = nanoTime.getAsLong();
        AtomicBoolean closed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
            (proxy, method, args) -> {
                if (method.getName().equals("close")) {
                    try { return invoke(connection, method, args); }
                    finally { if (closed.compareAndSet(false, true)) trace.held += nanoTime.getAsLong() - heldStart; }
                }
                if (method.getName().equals("commit") || method.getName().equals("rollback")) {
                    long before = nanoTime.getAsLong();
                    try { return invoke(connection, method, args); }
                    finally { trace.completion += nanoTime.getAsLong() - before; }
                }
                Object result = invoke(connection, method, args);
                if (result instanceof Statement statement) {
                    String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : "";
                    return statement(statement, sql, trace);
                }
                return result;
            });
    }

    private Statement statement(Statement statement, String preparedSql, Trace trace) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
            : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                if (!method.getName().startsWith("execute")) return invoke(statement, method, args);
                String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : preparedSql;
                long before = nanoTime.getAsLong();
                try { return invoke(statement, method, args); }
                finally {
                    long elapsed = nanoTime.getAsLong() - before;
                    trace.sql += elapsed;
                    trace.statements++;
                    String normalized = sql.replace("`", "").stripLeading().toLowerCase(Locale.ROOT);
                    if (normalized.startsWith("update coupon_campaign ")) trace.update += elapsed;
                    if (normalized.startsWith("insert into coupon ") || normalized.startsWith("insert into coupon(")) trace.insert += elapsed;
                    if (normalized.startsWith("select ")) trace.select += elapsed;
                }
            });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static final class Trace {
        private final String kind;
        private final String requestId;
        private final long start;
        private final long startEpochMs = System.currentTimeMillis();
        private long acquire;
        private long held;
        private long sql;
        private long update;
        private long insert;
        private long select;
        private long completion;
        private int connections;
        private int statements;
        private int acquireFailures;
        private Trace(String kind, String requestId, long start) { this.kind = kind; this.requestId = requestId; this.start = start; }
    }
}
