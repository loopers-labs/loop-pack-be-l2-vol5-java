package com.loopers.infrastructure.persistence.coupon.support;

import com.loopers.application.coupon.port.CouponExecutionEvidence;
import com.loopers.application.coupon.support.CouponExecution;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class JdbcCouponExecutionEvidence implements CouponExecutionEvidence {
    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean ended(CouponExecution.DbWork work, long campaignId) {
        try {
            return ended(jdbc, work, campaignId);
        } catch (RuntimeException failure) {
            log.warn("coupon DB termination evidence unavailable", failure);
            return false;
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean readyAfterRestart(List<CouponExecution.DbWork> previousSessions) {
        try {
            // 이전 boot registry는 종료 증거가 아니다. 최초 개방은 DB의 다른 TX가 없는 정지 구간에서만 허용한다.
            if (previousSessions == null || !canInspectAllSessions(jdbc)
                || previousSessions.stream().anyMatch(work -> work == null || !sessionGone(jdbc, work))) {
                return false;
            }
            Long active = jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM information_schema.innodb_trx "
                + "WHERE trx_mysql_thread_id <> CONNECTION_ID()) + "
                + "(SELECT COUNT(*) FROM information_schema.processlist WHERE id <> CONNECTION_ID() "
                + "AND command NOT IN ('Sleep', 'Daemon', 'Binlog Dump', 'Binlog Dump GTID'))", Long.class);
            return active != null && active == 0 && consistent(jdbc, null);
        } catch (RuntimeException failure) {
            log.warn("coupon restart evidence unavailable", failure);
            return false;
        }
    }

    private boolean ended(JdbcTemplate probe, CouponExecution.DbWork work, long campaignId) {
        return sessionGone(probe, work) && consistent(probe, campaignId);
    }

    private boolean sessionGone(JdbcTemplate probe, CouponExecution.DbWork work) {
        Long observerConnection = probe.queryForObject("SELECT CONNECTION_ID()", Long.class);
        if (observerConnection == null || observerConnection == work.connectionId() || !canInspectAllSessions(probe)
            || !work.serverId().equals(probe.queryForObject("SELECT @@server_uuid", String.class))) {
            return false;
        }
        // INNODB_TRX와 PROCESSLIST의 갱신 시점은 다를 수 있다. Sleep은 종료 증거가 아니다.
        // 원 세션 자체가 사라졌을 때만 UNKNOWN 실행을 복구한다.
        Long active = probe.queryForObject("SELECT (SELECT COUNT(*) FROM information_schema.innodb_trx "
            + "WHERE trx_mysql_thread_id = ?) + (SELECT COUNT(*) FROM information_schema.processlist "
            + "WHERE id = ?)", Long.class, work.connectionId(), work.connectionId());
        return active != null && active == 0;
    }

    private boolean consistent(JdbcTemplate probe, Long campaignId) {
        if (campaignId != null) {
            Long campaigns = probe.queryForObject("SELECT COUNT(*) FROM coupon_campaigns WHERE id = ?", Long.class, campaignId);
            if (campaigns == null || campaigns != 1) {
                return false;
            }
        }
        String sql = "SELECT COUNT(*) FROM coupon_campaigns c WHERE "
            + (campaignId == null ? "" : "c.id = ? AND ")
            + "(c.issued_count <> (SELECT COUNT(*) FROM coupons q WHERE q.campaign_id = c.id) "
            + "OR c.issued_count <> (SELECT COUNT(*) FROM coupon_slots s "
            + "WHERE s.campaign_id = c.id AND s.state = 'ISSUED') "
            + "OR c.total_quota <> (SELECT COUNT(*) FROM coupon_slots s WHERE s.campaign_id = c.id) "
            + "OR EXISTS (SELECT 1 FROM coupons q JOIN coupon_slots s ON s.id = q.slot_id "
            + "AND s.campaign_id = q.campaign_id WHERE q.campaign_id = c.id AND s.state <> 'ISSUED') "
            + "OR EXISTS (SELECT 1 FROM coupon_slots s WHERE s.campaign_id = c.id AND s.state = 'ISSUED' "
            + "AND NOT EXISTS (SELECT 1 FROM coupons q WHERE q.slot_id = s.id AND q.campaign_id = s.campaign_id)))";
        Long invalid = campaignId == null ? probe.queryForObject(sql, Long.class)
            : probe.queryForObject(sql, Long.class, campaignId);
        return invalid != null && invalid == 0;
    }

    private boolean canInspectAllSessions(JdbcTemplate probe) {
        // PROCESS 없는 PROCESSLIST는 자기 세션만 보여 줄 수 있다. 부분 가시성은 종료 증거가 아니다.
        // 역할에 간접 위임된 권한은 여기서 추정하지 않고 직접 전역 권한만 인정한다.
        return probe.queryForList("SHOW GRANTS FOR CURRENT_USER", String.class).stream().anyMatch(grant -> {
            String normalized = grant.toUpperCase(Locale.ROOT);
            int end = normalized.indexOf(" ON *.* TO ");
            if (!normalized.startsWith("GRANT ") || end < 0) {
                return false;
            }
            return Arrays.stream(normalized.substring(6, end).split(","))
                .map(String::trim).anyMatch(privilege -> privilege.equals("PROCESS") || privilege.equals("ALL PRIVILEGES"));
        });
    }
}
