package com.loopers.domains.coupon.out;

import com.loopers.domains.coupon.app.port.AdmissionUnavailableException;
import com.loopers.domains.coupon.app.port.CouponAdmissionInspection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 재개 확인 모드의 DB 조회(D140). 읽기만 한다. 잠금 조회에는 DB 계정의 performance_schema 읽기 권한이 필요하고,
// 없거나 조회가 실패하면 포트 예외로 바꿔 확인 모드가 ✗(열지 말 것)로 보고하게 한다.
// 열린 트랜잭션 수는 data_locks의 트랜잭션 ID로 센다. 잠금은 트랜잭션이 열려 있는 동안만 남는다.
// innodb_trx와 JOIN하면 실제 MySQL 8에서 잠금을 쥔 트랜잭션이 innodb_trx에 보이지 않는 경우가 있어(테스트에서 확인) 쓰지 않는다.
@Repository
public class CouponAdmissionInspectionAdapter implements CouponAdmissionInspection {
    private static final String OPEN_LOCK_TRANSACTIONS = "select count(distinct l.engine_transaction_id)"
        + " from performance_schema.data_locks l"
        + " where l.object_schema = database() and l.object_name in ('coupon_campaign', 'issued_coupon')";
    private static final String ISSUED_COUNT_MISMATCHES = "select c.id from coupon_campaign c"
        + " left join (select campaign_id, count(*) as issued from issued_coupon group by campaign_id) i on i.campaign_id = c.id"
        + " where c.issued_count <> coalesce(i.issued, 0) order by c.id";

    private final JdbcTemplate jdbcTemplate;

    public CouponAdmissionInspectionAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int openCouponLockTransactions() {
        try {
            Integer count = jdbcTemplate.queryForObject(OPEN_LOCK_TRANSACTIONS, Integer.class);
            return count == null ? 0 : count;
        } catch (RuntimeException e) {
            throw new AdmissionUnavailableException("쿠폰 잠금 트랜잭션 조회 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                e);
        }
    }

    @Override
    public List<Long> issuedCountMismatches() {
        try {
            return jdbcTemplate.queryForList(ISSUED_COUNT_MISMATCHES, Long.class);
        } catch (RuntimeException e) {
            throw new AdmissionUnavailableException("발급 수 대조 조회 실패: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
