package com.loopers.config.preservation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;

final class ConnectionObservation implements BeanPostProcessor {
    private final AtomicInteger sequence = new AtomicInteger();
    private final List<Event> events = new ArrayList<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if ("mySqlMainDataSource".equals(name) && bean instanceof DataSource dataSource) {
            return new ObservedDataSource(dataSource);
        }
        return bean;
    }

    void clear() {
        events.clear();
    }

    List<Event> events() {
        return List.copyOf(events);
    }

    record Event(int connectionId, String operation, String sql, boolean autoCommit) {}

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
                        events.add(new Event(id, "sql", sql, connection.getAutoCommit()));
                    } else if ("commit".equals(operation) || "rollback".equals(operation)) {
                        events.add(new Event(id, operation, "", connection.getAutoCommit()));
                    }
                    try {
                        return method.invoke(connection, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        }
    }
}
