package com.loopers.support;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestSqlProbeTest {

    @Test
    void recordsExecutionRatherThanPreparationAndPreservesResultIdentity() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        String sql = "  select  id\n from product\t where id = ?  ";
        when(raw.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        RequestSqlProbe probe = new RequestSqlProbe(() -> "read-1");
        Connection connection = probe.wrap(raw);
        connection.prepareStatement(sql).executeQuery();

        probe.start();
        PreparedStatement query = connection.prepareStatement(sql);
        query.setLong(1, 10);
        assertThat(probe.stop()).isEmpty();
        probe.start();
        assertThat(query.executeQuery()).isSameAs(result);
        List<RequestSqlProbe.Event> events = probe.stop();

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().requestId()).isEqualTo("read-1");
        assertThat(events.getFirst().sql()).isEqualTo("select id from product where id = ?");
        assertThat(events.getFirst().success()).isTrue();
        assertThat(events.getFirst().executeNanos()).isGreaterThanOrEqualTo(0);
        verify(statement).setLong(1, 10);
        query.executeQuery();
        assertThat(probe.stop()).isEqualTo(events);
    }

    @Test
    void recordsAllFourPreparedExecutionMethodsIncludingRevisionUpsert() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        String sql = "insert into product_list_revision values (1, 1) on duplicate key update revision = revision + 1";
        when(raw.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(statement.executeUpdate()).thenReturn(2);
        when(statement.executeLargeUpdate()).thenReturn(3L);
        when(statement.execute()).thenReturn(true);
        RequestSqlProbe probe = new RequestSqlProbe(() -> "write-1");
        PreparedStatement query = probe.wrap(raw).prepareStatement(sql);

        probe.start();
        assertThat(query.executeQuery()).isSameAs(result);
        assertThat(query.executeUpdate()).isEqualTo(2);
        assertThat(query.executeLargeUpdate()).isEqualTo(3L);
        assertThat(query.execute()).isTrue();

        assertThat(probe.stop()).hasSize(4).allSatisfy(event -> {
            assertThat(event.requestId()).isEqualTo("write-1");
            assertThat(event.sql()).isEqualTo(sql);
            assertThat(event.success()).isTrue();
            assertThat(event.executeNanos()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    void recordsFailureAndRethrowsTheExactDatabaseException() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        SQLException failure = new SQLException("test-only deadlock", "40001", 1213);
        when(raw.prepareStatement("update product set stock_quantity = ? where id = ?")).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(failure);
        RequestSqlProbe probe = new RequestSqlProbe(() -> "write-failed");
        PreparedStatement query = probe.wrap(raw).prepareStatement("update product set stock_quantity = ? where id = ?");
        probe.start();

        assertThatThrownBy(query::executeUpdate).isSameAs(failure);
        assertThat(probe.stop()).hasSize(1).allSatisfy(event -> {
            assertThat(event.requestId()).isEqualTo("write-failed");
            assertThat(event.success()).isFalse();
            assertThat(event.executeNanos()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    void attributesReusedStatementsAtExecutionAndIgnoresMissingRequestIds() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(raw.prepareStatement("select 1")).thenReturn(statement);
        AtomicReference<String> requestId = new AtomicReference<>();
        RequestSqlProbe probe = new RequestSqlProbe(requestId::get);
        PreparedStatement query = probe.wrap(raw).prepareStatement("select 1");
        probe.start();
        query.executeQuery();
        requestId.set("read-a");
        query.executeQuery();
        requestId.set("read-b");
        query.executeQuery();
        requestId.set(null);
        query.executeQuery();

        assertThat(probe.stop()).extracting(RequestSqlProbe.Event::requestId).containsExactly("read-a", "read-b");
    }

    @Test
    void attributesConcurrentExecutionsToTheirOwnRequestThread() throws Exception {
        ThreadLocal<String> requestId = new ThreadLocal<>();
        RequestSqlProbe probe = new RequestSqlProbe(requestId::get);
        CountDownLatch executing = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        List<Future<Void>> workers = new ArrayList<>();
        probe.start();
        try {
            for (int index = 0; index < 4; index++) {
                String id = "worker-" + index;
                workers.add(executor.submit(() -> {
                    requestId.set(id);
                    try {
                        Connection raw = mock(Connection.class);
                        PreparedStatement statement = mock(PreparedStatement.class);
                        when(raw.prepareStatement("select revision from product_list_revision")).thenReturn(statement);
                        when(statement.executeQuery()).thenAnswer(invocation -> {
                            executing.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new SQLException("test worker release timed out");
                            }
                            return null;
                        });
                        PreparedStatement query = probe.wrap(raw).prepareStatement("select revision from product_list_revision");
                        query.executeQuery();
                        query.executeQuery();
                        return null;
                    } finally {
                        requestId.remove();
                    }
                }));
            }
            assertThat(executing.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<Void> worker : workers) {
                worker.get(5, TimeUnit.SECONDS);
            }
            assertThat(probe.stop()).hasSize(8).extracting(RequestSqlProbe.Event::requestId)
                .containsExactlyInAnyOrder("worker-0", "worker-0", "worker-1", "worker-1",
                    "worker-2", "worker-2", "worker-3", "worker-3");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            probe.stop();
        }
    }

    @Test
    void preservesWrapperIdentityAndConnectionAssociation() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(raw.prepareStatement("select 1")).thenReturn(statement);
        RequestSqlProbe probe = new RequestSqlProbe(() -> "read-1");
        Connection connection = probe.wrap(raw);
        PreparedStatement query = connection.prepareStatement("select 1");

        assertThat(connection.equals(connection)).isTrue();
        assertThat(connection.equals(raw)).isFalse();
        assertThat(connection.hashCode()).isEqualTo(System.identityHashCode(connection));
        assertThat(connection.unwrap(Connection.class)).isSameAs(connection);
        assertThat(connection.isWrapperFor(Connection.class)).isTrue();
        assertThat(query.equals(query)).isTrue();
        assertThat(query.equals(statement)).isFalse();
        assertThat(query.hashCode()).isEqualTo(System.identityHashCode(query));
        assertThat(query.unwrap(PreparedStatement.class)).isSameAs(query);
        assertThat(query.isWrapperFor(PreparedStatement.class)).isTrue();
        assertThat(query.getConnection()).isSameAs(connection);
        connection.close();
        query.close();
        verify(raw).close();
        verify(statement).close();
    }

    @Test
    void rejectsOverlappingMeasurementSessionsAndClearsPreviousEvents() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(raw.prepareStatement("select 1")).thenReturn(statement);
        RequestSqlProbe probe = new RequestSqlProbe(() -> "read-1");
        probe.start();
        probe.wrap(raw).prepareStatement("select 1").executeQuery();
        assertThatThrownBy(probe::start).isInstanceOf(IllegalStateException.class);
        assertThat(probe.stop()).hasSize(1);
        probe.start();
        assertThat(probe.stop()).isEmpty();
    }

    @Test
    void doesNotCarryAnUnfinishedExecutionIntoTheNextMeasurementSession() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(raw.prepareStatement("select 1")).thenReturn(statement);
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(statement.executeQuery()).thenAnswer(invocation -> {
            executing.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new SQLException("test worker release timed out");
            }
            return null;
        });
        RequestSqlProbe probe = new RequestSqlProbe(() -> "read-old");
        PreparedStatement query = probe.wrap(raw).prepareStatement("select 1");
        var executor = Executors.newSingleThreadExecutor();
        probe.start();
        try {
            Future<ResultSet> worker = executor.submit(() -> {
                return query.executeQuery();
            });
            assertThat(executing.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(probe.stop()).isEmpty();
            probe.start();
            release.countDown();
            worker.get(5, TimeUnit.SECONDS);
            assertThat(probe.stop()).isEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            probe.stop();
        }
    }
}
