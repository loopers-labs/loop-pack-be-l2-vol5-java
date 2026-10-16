package com.loopers.support;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcReadProbeTest {

    @Test
    void capturesSuccessfulPreparedReadsWithOrderedBindingsOnlyInsideMeasurement() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        String sql = "select id from product where brand_id = ? limit ?";
        when(raw.prepareStatement(sql)).thenReturn(statement);
        JdbcReadProbe probe = new JdbcReadProbe();
        Connection connection = probe.wrap(raw);
        PreparedStatement query = connection.prepareStatement(sql);
        query.setInt(2, 20);
        query.setLong(1, 1007);
        query.executeQuery();
        probe.start();
        query.executeQuery();
        query.setInt(2, 10);
        query.executeQuery();
        List<JdbcReadProbe.Query> captured = probe.stop();

        assertThat(captured).hasSize(2);
        assertThat(captured.getFirst().sql()).isEqualTo(sql);
        assertThat(captured.getFirst().parameters()).containsExactly(1007L, 20);
        assertThat(captured.getLast().parameters()).containsExactly(1007L, 10);
        assertThat(captured).allSatisfy(value -> assertThat(value.executeNanos()).isGreaterThanOrEqualTo(0));
        assertThat(query.getConnection()).isSameAs(connection);
        query.executeQuery();
        assertThat(probe.stop()).isEqualTo(captured);
    }

    @Test
    void capturesHttpWorkerThreadWithoutCountingFixtureOrExplainStatements() throws Exception {
        Connection raw = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(raw.createStatement()).thenReturn(statement);
        JdbcReadProbe probe = new JdbcReadProbe();
        Connection connection = probe.wrap(raw);
        connection.createStatement().executeQuery("select 1");
        probe.start();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> connection.createStatement().executeQuery("/* measured */ select 2"))
                .get(5, TimeUnit.SECONDS);
        }
        connection.createStatement().executeQuery("EXPLAIN SELECT 2");
        assertThat(probe.stop()).extracting(JdbcReadProbe.Query::sql).containsExactly("/* measured */ select 2");
        connection.createStatement().executeQuery("EXPLAIN SELECT 2");
        assertThat(probe.stop()).hasSize(1);
        probe.start();
        assertThat(probe.stop()).isEmpty();
    }

    @Test
    void preservesNullBindingAndClearsReusedParameters() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        String sql = "select id from product where name = ?";
        when(raw.prepareStatement(sql)).thenReturn(statement);
        JdbcReadProbe probe = new JdbcReadProbe();
        PreparedStatement query = probe.wrap(raw).prepareStatement(sql);
        probe.start();
        query.setNull(1, java.sql.Types.VARCHAR);
        query.executeQuery();
        query.clearParameters();
        query.setString(1, "상품");
        query.executeQuery();

        assertThat(probe.stop().getFirst().parameters()).containsExactly((Object) null);
        assertThat(probe.stop().getLast().parameters()).containsExactly("상품");
    }

    @Test
    void propagatesDatabaseFailureWithoutRecordingSuccessfulExecution() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(raw.prepareStatement("select id from product")).thenReturn(statement);
        SQLException failure = new SQLException("test-only read failed");
        when(statement.executeQuery()).thenThrow(failure);
        JdbcReadProbe probe = new JdbcReadProbe();
        probe.start();

        assertThatThrownBy(() -> probe.wrap(raw).prepareStatement("select id from product").executeQuery())
            .isSameAs(failure);
        assertThat(probe.stop()).isEmpty();
    }

    @Test
    void rejectsOverlappingMeasurementSessions() {
        JdbcReadProbe probe = new JdbcReadProbe();
        probe.start();
        assertThatThrownBy(probe::start).isInstanceOf(IllegalStateException.class);
        probe.stop();
        probe.start();
        assertThat(probe.stop()).isEmpty();
    }
}
