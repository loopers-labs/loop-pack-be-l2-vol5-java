package experiments.coupon;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcTimingProbeTest {
    @Test
    void separatesAcquisitionHoldingSqlAndCommitWithoutDoubleCountingClose() throws Exception {
        AtomicLong clock = new AtomicLong();
        JdbcTimingProbe probe = new JdbcTimingProbe(clock::get);
        Connection delegate = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        String sql = "update coupon_campaign set issued_count=issued_count+1 where id=?";
        when(delegate.prepareStatement(sql)).thenReturn(statement);
        when(statement.executeUpdate()).thenAnswer(call -> { clock.addAndGet(30); return 1; });
        doAnswer(call -> { clock.addAndGet(5); return null; }).when(delegate).commit();
        probe.begin("issue", "request-one");
        Connection connection = probe.acquire(() -> { clock.addAndGet(100); return delegate; });
        clock.addAndGet(10);
        assertThat(connection.prepareStatement(sql).executeUpdate()).isEqualTo(1);
        connection.commit();
        connection.close();
        connection.close();
        var row = probe.end(201);
        assertThat(row.requestId()).isEqualTo("request-one");
        assertThat(row.acquireNanos()).isEqualTo(100);
        assertThat(row.heldNanos()).isEqualTo(45);
        assertThat(row.sqlNanos()).isEqualTo(30);
        assertThat(row.couponUpdateNanos()).isEqualTo(30);
        assertThat(row.completionNanos()).isEqualTo(5);
        assertThat(row.totalNanos()).isEqualTo(145);
        assertThat(row.connections()).isEqualTo(1);
    }

    @Test
    void preservesAcquireAndSqlFailuresAndClearsThreadContext() throws Exception {
        AtomicLong clock = new AtomicLong();
        JdbcTimingProbe probe = new JdbcTimingProbe(clock::get);
        SQLException failure = new SQLException("expected");
        probe.begin("issue");
        assertThatThrownBy(() -> probe.acquire(() -> { clock.addAndGet(100); throw failure; })).isSameAs(failure);
        var first = probe.end(503);
        assertThat(first.acquireNanos()).isEqualTo(100);
        assertThat(first.acquireFailures()).isEqualTo(1);
        assertThat(first.connections()).isZero();
        Connection delegate = mock(Connection.class);
        assertThat(probe.acquire(() -> delegate)).isSameAs(delegate);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.prepareStatement("select 1")).thenReturn(statement);
        when(statement.executeQuery()).thenAnswer(call -> { clock.addAndGet(20); throw failure; });
        probe.begin("product");
        Connection connection = probe.acquire(() -> delegate);
        assertThatThrownBy(() -> connection.prepareStatement("select 1").executeQuery()).isSameAs(failure);
        connection.close();
        var second = probe.end(500);
        assertThat(second.sqlNanos()).isEqualTo(20);
        assertThat(second.selectNanos()).isEqualTo(20);
        assertThat(second.acquireFailures()).isZero();
    }
}
