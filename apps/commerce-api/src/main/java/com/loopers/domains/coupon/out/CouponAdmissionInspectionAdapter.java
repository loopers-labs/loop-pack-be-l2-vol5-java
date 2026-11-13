package com.loopers.domains.coupon.out;

import com.loopers.domains.coupon.app.port.CouponAdmissionInspection;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CouponAdmissionInspectionAdapter implements CouponAdmissionInspection {
    private final JdbcTemplate jdbcTemplate;

    public CouponAdmissionInspectionAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int openCouponLockTransactions() {
        return 0;
    }

    @Override
    public List<Long> issuedCountMismatches() {
        return List.of();
    }
}
