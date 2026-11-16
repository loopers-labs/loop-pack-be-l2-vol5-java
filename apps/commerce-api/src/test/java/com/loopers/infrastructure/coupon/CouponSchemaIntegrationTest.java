package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.support.error.CoreException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 빈 테스트 MySQL 에 Hibernate 가 만든 쿠폰 스키마를 상세설계 3.1·3.2 와 비교한다.
 * 인덱스는 존재·열 순서만 확인하며 실행계획·성능 효과를 검증하지 않는다.
 */
@SpringBootTest
@DisplayName("쿠폰 스키마는 설계의 열·NOT NULL·named 제약·목록 인덱스를 갖고 물리 FK 는 없다.")
class CouponSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private CouponCampaignRepository campaignRepository;
    @Autowired
    private CouponRepository couponRepository;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("업무 열은 설계 타입이고 모두 NOT NULL 이다.")
    @Test
    void columns() {
        Map<String, String> campaign = columns("coupon_campaign");
        Map<String, String> coupon = columns("coupon");
        System.out.println("[COUPON SCHEMA] coupon_campaign=" + campaign + " coupon=" + coupon);

        assertAll(
            () -> assertThat(campaign).containsEntry("id", "bigint NO")
                .containsEntry("name", "varchar(100) NO")
                .containsEntry("status", "varchar(16) NO")
                .containsEntry("total_quota", "bigint NO")
                .containsEntry("issued_count", "bigint NO")
                .containsEntry("benefit_type", "varchar(16) NO")
                .containsEntry("benefit_value", "bigint NO")
                .containsEntry("created_at", "datetime(6) NO")
                .containsEntry("updated_at", "datetime(6) NO"),
            () -> assertThat(coupon).containsEntry("id", "bigint NO")
                .containsEntry("campaign_id", "bigint NO")
                .containsEntry("user_id", "bigint NO")
                .containsEntry("benefit_type", "varchar(16) NO")
                .containsEntry("benefit_value", "bigint NO")
                .containsEntry("created_at", "datetime(6) NO"),
            () -> assertThat(coupon).doesNotContainKeys("campaign_name", "issued_at", "status")
        );
    }

    @DisplayName("named unique·목록 인덱스·CHECK 가 있고 두 테이블에 물리 FK 가 없다.")
    @Test
    void constraintsAndIndexes() {
        Map<String, String> couponIndexes = indexes("coupon");
        List<String> checks = jdbcTemplate.queryForList("""
            SELECT CONCAT(tc.CONSTRAINT_NAME, '=', cc.CHECK_CLAUSE) FROM information_schema.TABLE_CONSTRAINTS tc
            JOIN information_schema.CHECK_CONSTRAINTS cc
              ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
            WHERE tc.TABLE_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'coupon_campaign' AND tc.CONSTRAINT_TYPE = 'CHECK'
            """, String.class);
        Long foreignKeys = jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
            WHERE CONSTRAINT_SCHEMA = DATABASE()
              AND (TABLE_NAME IN ('coupon', 'coupon_campaign') OR REFERENCED_TABLE_NAME IN ('coupon', 'coupon_campaign'))
            """, Long.class);
        System.out.println("[COUPON SCHEMA] coupon indexes=" + couponIndexes + " campaign checks=" + checks + " fk=" + foreignKeys);

        assertAll(
            () -> assertThat(couponIndexes).containsEntry("uk_coupon_campaign_user", "unique:campaign_id,user_id"),
            () -> assertThat(couponIndexes).containsEntry("idx_coupon_user_created", "index:user_id,created_at,id"),
            () -> assertThat(checks).singleElement().satisfies(check -> assertThat(check.replace("`", ""))
                .startsWith("ck_coupon_campaign_quota=")
                .contains("total_quota >= 1").contains("issued_count >= 0").contains("issued_count <= total_quota")),
            () -> assertThat(foreignKeys).isZero()
        );
    }

    @DisplayName("CHECK 는 총량 0·발급 수 음수·총량 초과 쓰기를 막고, unique 는 같은 캠페인·사용자 두 번째 행을 막는다.")
    @Test
    void enforcesConstraints() {
        Long campaignId = transactionTemplate.execute(status -> campaignRepository.save(
            CouponCampaignModel.create("c", 2L, CouponBenefitType.FIXED_AMOUNT, 1_000L)).getId());
        jdbcTemplate.update("INSERT INTO coupon (campaign_id, user_id, benefit_type, benefit_value, created_at, updated_at) "
            + "VALUES (?, 1, 'FIXED_AMOUNT', 1000, NOW(6), NOW(6))", campaignId);

        assertAll(
            // MySQL CHECK 위반(3819)은 Spring 이 DataIntegrityViolationException 으로 분류하지 않는다.
            () -> assertCheckViolation("UPDATE coupon_campaign SET total_quota = 0, issued_count = 0 WHERE id = " + campaignId),
            () -> assertCheckViolation("UPDATE coupon_campaign SET issued_count = -1 WHERE id = " + campaignId),
            () -> assertCheckViolation("UPDATE coupon_campaign SET issued_count = 3 WHERE id = " + campaignId),
            () -> assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO coupon (campaign_id, user_id, benefit_type, benefit_value, created_at, updated_at) "
                    + "VALUES (?, 1, 'PERCENTAGE', 10, NOW(6), NOW(6))", campaignId))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_coupon_campaign_user"),
            () -> assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO coupon (campaign_id, user_id, benefit_type, benefit_value, created_at, updated_at) "
                    + "VALUES (?, NULL, 'FIXED_AMOUNT', 1, NOW(6), NOW(6))", campaignId))
                .as("NOT NULL").isInstanceOf(DataIntegrityViolationException.class),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Long.class,
                campaignId)).isZero()
        );
    }

    @DisplayName("변환기는 enum 이름을 저장하고 같은 값으로 읽으며, 알 수 없는 저장 값은 읽기 실패로 드러낸다.")
    @Test
    void convertersRoundTrip() {
        Long campaignId = transactionTemplate.execute(status -> campaignRepository.save(
            CouponCampaignModel.create("정률", 1L, CouponBenefitType.PERCENTAGE, 15L)).getId());
        Map<String, Object> raw = jdbcTemplate.queryForMap(
            "SELECT status, benefit_type FROM coupon_campaign WHERE id = ?", campaignId);
        CouponCampaignModel reloaded = transactionTemplate.execute(status -> campaignRepository.findById(campaignId).orElseThrow());

        jdbcTemplate.update("UPDATE coupon_campaign SET status = 'PAUSED' WHERE id = ?", campaignId);

        assertAll(
            () -> assertThat(raw).containsEntry("status", "ACTIVE").containsEntry("benefit_type", "PERCENTAGE"),
            () -> assertThat(reloaded.getBenefitType()).isEqualTo(CouponBenefitType.PERCENTAGE),
            () -> assertThat(reloaded.getBenefitValue()).isEqualTo(15L),
            () -> assertThatThrownBy(() -> transactionTemplate.execute(status -> campaignRepository.findById(campaignId)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
        );
    }

    @DisplayName("영속화 시점 guard 는 생성 뒤 범위를 벗어난 값의 INSERT 를 막는다.")
    @Test
    void persistGuard() {
        CouponCampaignModel campaign = CouponCampaignModel.create("c", 1L, CouponBenefitType.PERCENTAGE, 100L);
        ReflectionTestUtils.setField(campaign, "benefitValue", 101L);
        CouponModel coupon = CouponModel.issue(1L, 1L, CouponBenefitType.FIXED_AMOUNT, 1L);
        ReflectionTestUtils.setField(coupon, "benefitValue", 0L);

        assertAll(
            () -> assertThatThrownBy(() -> transactionTemplate.execute(status -> campaignRepository.save(campaign)))
                .isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> transactionTemplate.execute(status -> couponRepository.save(coupon)))
                .isInstanceOf(CoreException.class),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_campaign", Long.class)).isZero(),
            () -> assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon", Long.class)).isZero()
        );
    }

    private void assertCheckViolation(String sql) {
        assertThatThrownBy(() -> jdbcTemplate.update(sql)).isInstanceOf(UncategorizedSQLException.class)
            .satisfies(error -> assertThat(((UncategorizedSQLException) error).getSQLException().getErrorCode()).isEqualTo(3819))
            .hasMessageContaining("ck_coupon_campaign_quota");
    }

    private Map<String, String> columns(String table) {
        Map<String, String> columns = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION
            """, rs -> {
            columns.put(rs.getString(1), rs.getString(2) + " " + rs.getString(3));
        }, table);
        return columns;
    }

    private Map<String, String> indexes(String table) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        jdbcTemplate.query("""
            SELECT INDEX_NAME, NON_UNIQUE, COLUMN_NAME FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY INDEX_NAME, SEQ_IN_INDEX
            """, rs -> {
            columns.computeIfAbsent(rs.getString(1), name -> new ArrayList<>()).add(rs.getString(3));
            unique.put(rs.getString(1), rs.getInt(2) == 0);
        }, table);
        Map<String, String> result = new LinkedHashMap<>();
        columns.forEach((name, cols) -> result.put(name, (unique.get(name) ? "unique:" : "index:") + String.join(",", cols)));
        return result;
    }
}
