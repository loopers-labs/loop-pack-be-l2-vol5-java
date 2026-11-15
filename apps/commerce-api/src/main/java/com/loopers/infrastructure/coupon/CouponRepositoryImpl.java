package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class CouponRepositoryImpl implements CouponRepository {
    private final CouponJpaRepository coupons;
    private final JdbcTemplate jdbc;

    @Override
    public Optional<CouponModel> findByCampaignIdAndUserId(Long campaignId, Long userId) {
        return coupons.findByCampaignIdAndUserId(campaignId, userId);
    }

    @Override
    public CouponModel save(CouponModel coupon) {
        return coupons.save(coupon);
    }

    @Override
    public List<CouponModel> findAllByUserId(Long userId) {
        return coupons.findAllByUserIdOrderByIssuedAtDescIdDesc(userId);
    }

    @Override
    public List<Long> findUserIdsByCampaignId(Long campaignId) {
        return coupons.findUserIdsByCampaignId(campaignId);
    }

    @Override
    public List<CouponModel> findAllByCampaignIdAndUserIds(Long campaignId, Collection<Long> userIds) {
        if (userIds.isEmpty()) return List.of();
        return coupons.findAllByCampaignIdAndUserIdIn(campaignId, userIds);
    }

    /**
     * JPA는 IDENTITY 키라 INSERT를 묶어 보내지 못한다. 그래서 여러 행을 문장 하나로 보낸다.
     * 시각은 Hibernate 설정(jdbc.time_zone=UTC)과 같게 UTC로 저장한다. 같은 TX의 연결을 쓴다.
     */
    @Override
    public void insertAll(List<CouponModel> rows) {
        if (rows.isEmpty()) return;
        StringBuilder sql = new StringBuilder(
            "INSERT INTO coupon (campaign_id, user_id, issued_at, benefit_description, reservation_id, created_at, updated_at) VALUES ");
        List<Object> args = new ArrayList<>();
        LocalDateTime now = utc(Instant.now());
        for (int i = 0; i < rows.size(); i++) {
            CouponModel row = rows.get(i);
            sql.append(i == 0 ? "(?, ?, ?, ?, ?, ?, ?)" : ", (?, ?, ?, ?, ?, ?, ?)");
            args.add(row.getCampaignId());
            args.add(row.getUserId());
            args.add(utc(row.getIssuedAt()));
            args.add(row.getBenefitDescription());
            args.add(row.getReservationId());
            args.add(now);
            args.add(now);
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
