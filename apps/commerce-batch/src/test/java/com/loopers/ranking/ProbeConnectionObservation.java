package com.loopers.ranking;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Observes successful JDBC execution, rather than just statement preparation. */
final class ProbeConnectionObservation implements BeanPostProcessor {
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<Event> events = new CopyOnWriteArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        return "mySqlMainDataSource".equals(name) && bean instanceof DataSource dataSource
            ? new ObservedDataSource(dataSource) : bean;
    }

    void clear() {
        events.clear();
    }

    List<Event> events() {
        return List.copyOf(events);
    }

    record Event(int connectionId, String operation, String sql, boolean autoCommit) {}

    private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private final class ObservedDataSource extends DelegatingDataSource {
        private ObservedDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return observe(super.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return observe(super.getConnection(username, password));
        }

        private Connection observe(Connection connection) {
            int id = sequence.incrementAndGet();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    String operation = method.getName();
                    if ("prepareStatement".equals(operation) && arguments[0] instanceof String sql) {
                        var statement = (PreparedStatement) invoke(method, connection, arguments);
                        return observeStatement(statement, connection, id, sql);
                    }
                    if ("commit".equals(operation) || "rollback".equals(operation) || "close".equals(operation)) {
                        boolean autoCommit = connection.getAutoCommit();
                        Object result = invoke(method, connection, arguments);
                        events.add(new Event(id, operation, "", autoCommit));
                        return result;
                    }
                    return invoke(method, connection, arguments);
                });
        }

        private PreparedStatement observeStatement(PreparedStatement statement, Connection connection, int id, String sql) {
            return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    Object result = invoke(method, statement, arguments);
                    if (method.getName().startsWith("execute")) {
                        events.add(new Event(id, "sql", sql, connection.getAutoCommit()));
                    }
                    return result;
                });
        }
    }
}
