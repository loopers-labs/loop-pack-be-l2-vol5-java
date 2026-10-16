package com.loopers.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 동시 HTTP 요청별 실제 JDBC 실행을 관찰하는 테스트 전용 도구다. */
public final class RequestSqlProbe {
    private final Supplier<String> requestId;
    private final List<Event> events = new ArrayList<>();
    private boolean active;
    private long session;

    public RequestSqlProbe(Supplier<String> requestId) {
        this.requestId = Objects.requireNonNull(requestId);
    }

    public synchronized void start() {
        if (active) {
            throw new IllegalStateException("이전 측정을 종료한 뒤 새 측정을 시작해야 합니다.");
        }
        events.clear();
        session++;
        active = true;
    }

    public synchronized List<Event> stop() {
        active = false;
        return List.copyOf(events);
    }

    public Connection wrap(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return identity(proxy, method, arguments, connection);
                }
                if (isOwnWrapper(proxy, method, arguments)) {
                    return method.getName().equals("unwrap") ? proxy : true;
                }
                Object result = invoke(connection, method, arguments);
                if (result instanceof PreparedStatement statement && arguments != null && arguments.length > 0
                    && arguments[0] instanceof String sql) {
                    return wrapStatement(statement, (Connection) proxy, normalize(sql));
                }
                return result;
            });
    }

    private PreparedStatement wrapStatement(PreparedStatement statement, Connection connection, String preparedSql) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class : PreparedStatement.class;
        return (PreparedStatement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, arguments) -> {
                String name = method.getName();
                if (method.getDeclaringClass() == Object.class) {
                    return identity(proxy, method, arguments, statement);
                }
                if (isOwnWrapper(proxy, method, arguments)) {
                    return name.equals("unwrap") ? proxy : true;
                }
                if (name.equals("getConnection")) {
                    return connection;
                }
                if (isExecution(name)) {
                    long observedSession = activeSession();
                    String id = observedSession < 0 ? null : requestId.get();
                    if (id != null) {
                        String sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String value
                            ? normalize(value) : preparedSql;
                        long startedAt = System.nanoTime();
                        boolean success = false;
                        try {
                            Object result = invoke(statement, method, arguments);
                            success = true;
                            return result;
                        } finally {
                            record(observedSession, new Event(id, sql, System.nanoTime() - startedAt, success));
                        }
                    }
                }
                return invoke(statement, method, arguments);
            });
    }

    private synchronized long activeSession() {
        return active ? session : -1;
    }

    private synchronized void record(long observedSession, Event event) {
        if (active && session == observedSession) {
            events.add(event);
        }
    }

    private static boolean isExecution(String name) {
        return name.equals("executeQuery") || name.equals("executeUpdate")
            || name.equals("executeLargeUpdate") || name.equals("execute");
    }

    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    private static boolean isOwnWrapper(Object proxy, Method method, Object[] arguments) {
        return (method.getName().equals("unwrap") || method.getName().equals("isWrapperFor"))
            && arguments != null && arguments.length == 1 && arguments[0] instanceof Class<?> type && type.isInstance(proxy);
    }

    private static Object identity(Object proxy, Method method, Object[] arguments, Object delegate) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> delegate.toString();
        };
    }

    private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    /** executeNanos는 JDBC execute 반환까지이며 전체 결과 fetch나 HTTP 지연이 아니다. */
    public record Event(String requestId, String sql, long executeNanos, boolean success) {
    }
}
