package com.loopers.infrastructure.persistence.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.loopers.application.coupon.support.CouponExecution;
import com.loopers.infrastructure.persistence.coupon.support.JdbcCouponExecutionEvidence;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcCouponExecutionEvidenceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcCouponExecutionEvidence evidence = new JdbcCouponExecutionEvidence(jdbc);
    private final CouponExecution.DbWork work = new CouponExecution.DbWork("server-1", 10L);

    @BeforeEach
    void setUp() {
        when(jdbc.queryForList("SHOW GRANTS FOR CURRENT_USER", String.class))
            .thenReturn(List.of("GRANT PROCESS, SELECT ON *.* TO 'app'@'%'"));
        when(jdbc.queryForObject("SELECT @@server_uuid", String.class)).thenReturn("server-1");
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(10L), eq(10L))).thenReturn(0L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L))).thenReturn(0L);
        when(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_campaigns WHERE id = ?", Long.class, 1L)).thenReturn(1L);
    }

    @Test
    void partialSessionVisibilityIsNeverAcceptedAsTerminationEvidence() {
        when(jdbc.queryForList("SHOW GRANTS FOR CURRENT_USER", String.class))
            .thenReturn(List.of("GRANT SELECT ON `loopers`.* TO 'app'@'%'"));

        assertThat(evidence.ended(work, 1L)).isFalse();
        assertThat(evidence.readyAfterRestart(List.of())).isFalse();
    }

    @Test
    void globalPrivilegesAndConsistentDatabaseAllowEvidenceOnlyAfterSessionDisappears() {
        when(jdbc.queryForList("SHOW GRANTS FOR CURRENT_USER", String.class))
            .thenReturn(List.of("GRANT ALL PRIVILEGES ON *.* TO 'root'@'%' WITH GRANT OPTION"));

        assertThat(evidence.ended(work, 1L)).isTrue();
        assertThat(evidence.readyAfterRestart(List.of())).isTrue();
    }

    @Test
    void mismatchedServerAndActiveConnectionCannotConfirmTermination() {
        assertThat(evidence.ended(new CouponExecution.DbWork("other-server", 10L), 1L)).isFalse();
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(10L), eq(10L))).thenReturn(1L);

        assertThat(evidence.ended(work, 1L)).isFalse();
    }

    @Test
    void evenSleepingOriginalSessionIsNotTerminationEvidence() {
        when(jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM information_schema.innodb_trx "
            + "WHERE trx_mysql_thread_id = ?) + (SELECT COUNT(*) FROM information_schema.processlist WHERE id = ?)",
            Long.class, 10L, 10L)).thenReturn(1L);

        assertThat(evidence.ended(work, 1L)).isFalse();
    }

    @Test
    void missingCampaignOrBrokenBusinessCountsCannotConfirmTermination() {
        when(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_campaigns WHERE id = ?", Long.class, 1L)).thenReturn(0L);
        assertThat(evidence.ended(work, 1L)).isFalse();
        when(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_campaigns WHERE id = ?", Long.class, 1L)).thenReturn(1L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(1L))).thenReturn(1L);

        assertThat(evidence.ended(work, 1L)).isFalse();
    }

    @Test
    void permissionFailureKeepsRecoveryAndRestartClosed() {
        when(jdbc.queryForList("SHOW GRANTS FOR CURRENT_USER", String.class))
            .thenThrow(new PermissionDeniedDataAccessException("no access", null));

        assertThat(evidence.ended(work, 1L)).isFalse();
        assertThat(evidence.readyAfterRestart(List.of())).isFalse();
    }

    @Test
    void ordinaryRestartRejectsAnyPreviousSessionThatStillExists() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(10L), eq(10L))).thenReturn(1L);

        assertThat(evidence.readyAfterRestart(List.of(work))).isFalse();
    }

    @Test
    void ordinaryRestartRequiresPreviousSessionsToDisappearOnTheSameDatabase() {
        assertThat(evidence.readyAfterRestart(List.of(new CouponExecution.DbWork("other-server", 10L)))).isFalse();
        assertThat(evidence.readyAfterRestart(List.of(work))).isTrue();
    }
}
