package com.loopers.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** 격리된 순차 HTTP 측정 구간의 JDBC 조회를 관찰하는 테스트 전용 도구다. */
public final class JdbcReadProbe {
    private boolean active;
    private final List<Query> queries = new ArrayList<>();

    public synchronized void start() {
        if (active) {
            throw new IllegalStateException("측정 요청은 순차적으로 실행해야 합니다.");
        }
        queries.clear();
        active = true;
    }

    public synchronized List<Query> stop() {
        active = false;
        return List.copyOf(queries);
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
                if (result instanceof Statement statement) {
                    String sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String value
                        ? value : null;
                    return wrapStatement(statement, (Connection) proxy, sql);
                }
                return result;
            });
    }

    private Statement wrapStatement(Statement statement, Connection connection, String preparedSql) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
            : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        Map<Integer, Object> parameters = new TreeMap<>();
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
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
                String sql = arguments != null && arguments.length > 0 && arguments[0] instanceof String value
                    && name.startsWith("execute") ? value : preparedSql;
                if ((name.equals("executeQuery") || name.equals("execute")) && isSelect(sql) && isActive()) {
                    List<Object> bindings = Collections.unmodifiableList(new ArrayList<>(parameters.values()));
                    long start = System.nanoTime();
                    Object result = invoke(statement, method, arguments);
                    long duration = System.nanoTime() - start;
                    record(new Query(sql, bindings, duration));
                    return result;
                }
                Object result = invoke(statement, method, arguments);
                if (preparedSql != null && name.startsWith("set") && arguments != null
                    && arguments.length >= 2 && arguments[0] instanceof Integer index) {
                    parameters.put(index, name.equals("setNull") ? null : arguments[1]);
                } else if (name.equals("clearParameters")) {
                    parameters.clear();
                }
                return result;
            });
    }

    private synchronized boolean isActive() {
        return active;
    }

    private synchronized void record(Query query) {
        if (active) {
            queries.add(query);
        }
    }

    private static boolean isSelect(String sql) {
        if (sql == null) {
            return false;
        }
        String normalized = sql.stripLeading();
        while (normalized.startsWith("/*")) {
            int end = normalized.indexOf("*/");
            if (end < 0) {
                return false;
            }
            normalized = normalized.substring(end + 2).stripLeading();
        }
        return normalized.toLowerCase(Locale.ROOT).startsWith("select");
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

    /** executeNanos는 JDBC execute 반환까지이며 전체 fetch/HTTP 지연이 아니다. */
    public record Query(String sql, List<Object> parameters, long executeNanos) {
    }
}
