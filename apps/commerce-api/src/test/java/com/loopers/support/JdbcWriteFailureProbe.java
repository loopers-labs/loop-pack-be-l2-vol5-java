package com.loopers.support;

import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 테스트 요청의 실제 UPDATE 실행 뒤 다음 저장 경계에서 한 번만 실패시킨다. */
public final class JdbcWriteFailureProbe {
    private static final Pattern TARGET_UPDATE = Pattern.compile(
        "^update\\s+(?:[\\w`]+\\.)?`?(order|user|product)`?\\s+", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> OBSERVED_TABLES = Map.of(
        "brands", "brand", "products", "product", "orders", "order",
        "items", "order_item", "users", "user", "likes", "like");

    private boolean armed;
    private int failOnWrite;
    private Connection activeConnection;
    private int completedWrites;
    private int injectedFailures;
    private String failedSql;
    private boolean transactionActive;
    private boolean autoCommit;
    private final List<String> executedSql = new ArrayList<>();
    private Map<String, List<Map<String, Object>>> observedState = Map.of();

    public synchronized void arm(int writeNumber) {
        if (writeNumber < 2) {
            throw new IllegalArgumentException("실제 UPDATE 성공 후 실패하도록 2 이상의 순서를 지정해야 합니다.");
        }
        if (armed) {
            throw new IllegalStateException("이전 실패 주입을 먼저 종료해야 합니다.");
        }
        failOnWrite = writeNumber;
        activeConnection = null;
        completedWrites = 0;
        injectedFailures = 0;
        failedSql = null;
        transactionActive = false;
        autoCommit = false;
        executedSql.clear();
        observedState = Map.of();
        armed = true;
    }

    public synchronized void disarm() {
        armed = false;
        activeConnection = null;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(completedWrites, injectedFailures, failedSql, transactionActive, autoCommit,
            List.copyOf(executedSql), observedState);
    }

    public Connection wrap(Connection delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, arguments) -> {
                if (isProxyIdentityMethod(method)) {
                    return proxyIdentity(proxy, method, arguments, delegate);
                }
                if (isOwnWrapperMethod(proxy, method, arguments)) {
                    return method.getName().equals("unwrap") ? proxy : true;
                }
                Object result = invoke(delegate, method, arguments);
                if (result instanceof Statement statement) {
                    String sql = sqlArgument(arguments, null);
                    return wrapStatement(statement, (Connection) proxy, delegate, sql);
                }
                return result;
            });
    }

    private Statement wrapStatement(Statement delegate, Connection connection, Connection rawConnection, String preparedSql) {
        Class<?> statementType = delegate instanceof CallableStatement ? CallableStatement.class
            : delegate instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        boolean[] targetBatch = {false};
        return (Statement) Proxy.newProxyInstance(statementType.getClassLoader(), new Class<?>[]{statementType},
            (proxy, method, arguments) -> {
                String name = method.getName();
                if (isProxyIdentityMethod(method)) {
                    return proxyIdentity(proxy, method, arguments, delegate);
                }
                if (isOwnWrapperMethod(proxy, method, arguments)) {
                    return name.equals("unwrap") ? proxy : true;
                }
                if (name.equals("getConnection")) {
                    return connection;
                }
                String sql = sqlArgument(arguments, preparedSql);
                if (name.equals("addBatch") && isTargetUpdate(sql)) {
                    rejectBatchWhenArmed();
                    targetBatch[0] = true;
                }
                boolean executesBatch = name.equals("executeBatch") || name.equals("executeLargeBatch");
                if (executesBatch && (targetBatch[0] || isTargetUpdate(preparedSql))) {
                    rejectBatchWhenArmed();
                }
                if (isUpdateExecution(name) && isTargetUpdate(sql)) {
                    return executeWrite(rawConnection, delegate, method, arguments, sql);
                }
                Object result = invoke(delegate, method, arguments);
                if (executesBatch || name.equals("clearBatch")) {
                    targetBatch[0] = false;
                }
                return result;
            });
    }

    private synchronized Object executeWrite(Connection connection, Statement statement,
                                              Method method, Object[] arguments, String sql) throws Throwable {
        if (!armed) {
            return invoke(statement, method, arguments);
        }
        if (activeConnection == null) {
            activeConnection = connection;
        } else if (activeConnection != connection) {
            throw probeFailure("대상 UPDATE가 서로 다른 Connection에 섞였습니다.");
        }
        if (completedWrites + 1 == failOnWrite) {
            // raw Connection의 SELECT로 같은 트랜잭션의 미커밋 변경을 수집한다.
            transactionActive = TransactionSynchronizationManager.isActualTransactionActive();
            autoCommit = connection.getAutoCommit();
            observedState = readState(connection);
            failedSql = sql;
            injectedFailures++;
            armed = false;
            throw new SQLException("test-only: order write failed", "HY000", 0);
        }

        Object result = invoke(statement, method, arguments);
        long affected = result instanceof Number number ? number.longValue() : statement.getLargeUpdateCount();
        if (affected <= 0) {
            throw probeFailure("실제 변경 행이 없는 UPDATE를 성공 증거로 셀 수 없습니다.");
        }
        completedWrites++;
        executedSql.add(sql);
        return result;
    }

    private synchronized void rejectBatchWhenArmed() throws SQLException {
        if (armed) {
            throw probeFailure("UPDATE batch 실행은 지원하지 않습니다. 실행 증거 수집 방식을 먼저 확장해야 합니다.");
        }
    }

    private Map<String, List<Map<String, Object>>> readState(Connection connection) throws SQLException {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        for (Map.Entry<String, String> table : OBSERVED_TABLES.entrySet()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (PreparedStatement query = connection.prepareStatement("SELECT * FROM `" + table.getValue() + "` ORDER BY id");
                 ResultSet result = query.executeQuery()) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= columns; column++) {
                        row.put(result.getMetaData().getColumnLabel(column), JdbcUtils.getResultSetValue(result, column));
                    }
                    rows.add(Collections.unmodifiableMap(row));
                }
            }
            state.put(table.getKey(), List.copyOf(rows));
        }
        return Collections.unmodifiableMap(state);
    }

    private static boolean isUpdateExecution(String name) {
        return name.equals("executeUpdate") || name.equals("executeLargeUpdate") || name.equals("execute");
    }

    private static boolean isTargetUpdate(String sql) {
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
        return TARGET_UPDATE.matcher(normalized).find();
    }

    private static String sqlArgument(Object[] arguments, String preparedSql) {
        return arguments != null && arguments.length > 0 && arguments[0] instanceof String sql ? sql : preparedSql;
    }

    private static boolean isProxyIdentityMethod(Method method) {
        return method.getDeclaringClass() == Object.class;
    }

    private static Object proxyIdentity(Object proxy, Method method, Object[] arguments, Object delegate) {
        return switch (method.getName()) {
            case "equals" -> proxy == arguments[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> delegate.toString();
        };
    }

    private static boolean isOwnWrapperMethod(Object proxy, Method method, Object[] arguments) {
        return (method.getName().equals("unwrap") || method.getName().equals("isWrapperFor"))
            && arguments != null && arguments.length == 1 && arguments[0] instanceof Class<?> type && type.isInstance(proxy);
    }

    private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static SQLException probeFailure(String message) {
        return new SQLException("test-only probe configuration: " + message, "HY000", 0);
    }

    public record Snapshot(int completedWrites, int injectedFailures, String failedSql,
                           boolean transactionActive, boolean autoCommit, List<String> executedSql,
                           Map<String, List<Map<String, Object>>> observedState) {
    }
}
