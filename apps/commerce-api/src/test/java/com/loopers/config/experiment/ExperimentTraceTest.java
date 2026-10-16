package com.loopers.config.experiment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExperimentTraceTest {
    @AfterEach void clear() { ExperimentTrace.clear(); }

    @Test void nestedTimingsCountChildrenOnlyOnce() {
        AtomicLong clock = new AtomicLong();
        ExperimentTrace trace = new ExperimentTrace(clock::get);
        try (var parent = trace.scope("parent")) {
            clock.set(10_000_000);
            try (var child = trace.scope("child")) {
                clock.set(30_000_000);
            }
            clock.set(50_000_000);
        }
        assertThat(trace.timings().get("parent").totalMs()).isEqualTo(50);
        assertThat(trace.timings().get("parent").selfMs()).isEqualTo(30);
        assertThat(trace.timings().get("child").selfMs()).isEqualTo(20);
        assertThat(trace.timings().values().stream().mapToDouble(ExperimentTrace.Timing::selfMs).sum())
            .isEqualTo(50);
    }

    @Test void recordsResultReadingAndConnectionCommitSeparately() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(raw.prepareStatement("select id from product")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong(1)).thenReturn(7L);
        ExperimentTrace trace = new ExperimentTrace();
        ExperimentTrace.begin(trace);
        Connection connection = ExperimentTrace.wrap(raw);
        try (PreparedStatement query = connection.prepareStatement("select id from product");
             ResultSet result = query.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getLong(1)).isEqualTo(7);
            assertThat(result.next()).isFalse();
        }
        connection.commit();
        connection.close();
        assertThat(trace.timings().get("jdbc.execute").calls()).isEqualTo(1);
        assertThat(trace.timings().get("jdbc.result.read").calls()).isEqualTo(4);
        assertThat(trace.timings().get("jdbc.connection.commit").calls()).isEqualTo(1);
        assertThat(trace.timings().get("jdbc.connection.close").calls()).isEqualTo(1);
    }

    @Test void recordsFailedJdbcCallWithoutReplacingItsException() throws Exception {
        Connection raw = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        SQLException failure = new SQLException("failure", "42000");
        when(raw.prepareStatement("select ?")).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(failure);
        ExperimentTrace trace = new ExperimentTrace();
        ExperimentTrace.begin(trace);
        PreparedStatement wrapped = ExperimentTrace.wrap(raw).prepareStatement("select ?");
        assertThatThrownBy(wrapped::executeQuery).isSameAs(failure);
        assertThat(trace.queries).hasSize(1);
        assertThat(trace.queries.getFirst().sql()).isEqualTo("select ?");
        assertThat(trace.queries.getFirst().durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(trace.queries.getFirst().success()).isFalse();
        ExperimentTrace.clear();
        assertThatThrownBy(wrapped::executeQuery).isSameAs(failure);
        assertThat(trace.queries).hasSize(1);
    }

    @Test void rejectsUnboundedOrUnsafeCorrelationHeaders() {
        assertThat(ExperimentConfiguration.TraceValve.validId("baseline-01.1_2")).isTrue();
        assertThat(ExperimentConfiguration.TraceValve.validId("bad\"\nheader")).isFalse();
        assertThat(ExperimentConfiguration.TraceValve.validId("a".repeat(121))).isFalse();
        assertThat(ExperimentConfiguration.TraceValve.validId(null)).isFalse();
    }
}
