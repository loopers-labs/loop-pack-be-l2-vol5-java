package com.loopers.batch.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class JdbcPaymentReconciliationRepository implements PaymentReconciliationRepository {
    private static final RowMapper<PaymentRecord> PAYMENT_RECORD_MAPPER = (resultSet, rowNumber) ->
        new PaymentRecord(resultSet.getLong("id"), resultSet.getString("provider_order_id"),
            resultSet.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcPaymentReconciliationRepository(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<PaymentRecord> findRecentTerminalPayments(LocalDateTime from, LocalDateTime until, long afterId,
                                                         int limit) {
        return jdbcTemplate.query(
            "select p.id, p.provider_order_id, p.status from payments p"
                + " where p.status in ('SUCCESS', 'FAILED') and p.deleted_at is null and p.id > ?"
                + " and ((p.updated_at >= ? and p.updated_at < ?)"
                + " or exists (select 1 from payment_reconciliations r"
                + " where r.payment_id = p.id and r.status = 'UNRESOLVED'))"
                + " and not exists (select 1 from payment_reconciliations r"
                + " where r.payment_id = p.id and r.status in ('MATCHED', 'MISMATCH'))"
                + " order by p.id limit ?",
            PAYMENT_RECORD_MAPPER,
            afterId, java.sql.Timestamp.valueOf(from), java.sql.Timestamp.valueOf(until), limit
        );
    }

    @Override
    public void recordReconciliation(PaymentRecord payment, String reconciliationStatus, String providerStatus,
                                     String providerResponse, LocalDateTime checkedAt) {
        jdbcTemplate.update(
            "insert into payment_reconciliations"
                + " (payment_id, status, local_status, pg_status, pg_response, attempt_count, last_checked_at)"
                + " values (?, ?, ?, ?, ?, 1, ?)"
                + " on duplicate key update status = values(status), local_status = values(local_status),"
                + " pg_status = values(pg_status), pg_response = values(pg_response),"
                + " attempt_count = attempt_count + 1, last_checked_at = values(last_checked_at)",
            payment.id(), reconciliationStatus, payment.status(), providerStatus, providerResponse,
            java.sql.Timestamp.valueOf(checkedAt)
        );
    }

    @Override
    public List<PaymentRecord> findCancelProcessingPayments(long afterId, int limit) {
        return jdbcTemplate.query(
            "select id, provider_order_id, status from payments"
                + " where status = 'CANCEL_PROCESSING' and deleted_at is null and id > ? order by id limit ?",
            PAYMENT_RECORD_MAPPER, afterId, limit
        );
    }

    @Override
    public int markCanceled(long paymentId, String rawProviderResponse) {
        return jdbcTemplate.update(
            "update payments set status = 'CANCELED', raw_provider_response = ?, updated_at = current_timestamp"
                + " where id = ? and status = 'CANCEL_PROCESSING' and deleted_at is null",
            rawProviderResponse, paymentId
        );
    }

}
