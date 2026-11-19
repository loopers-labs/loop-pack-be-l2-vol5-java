package com.loopers.application.coupon;

import com.loopers.application.coupon.admission.CouponAdmissionSwitch;
import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.application.coupon.dto.CouponIssueInfo.Status;
import com.loopers.application.coupon.dto.CouponReconcileInfo;
import com.loopers.domain.coupon.CouponIssueTicket;
import com.loopers.domain.coupon.CouponIssueTicketRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.CouponAdmissionState;
import com.loopers.support.fixture.CouponCampaigns;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 관리자 기능이 DB 를 원본으로 Redis 수용 상태를 다시 만들고, 종료·마감 보정에서 약속한 결과를 지키는지 확인한다.
 * 실제 MySQL·Redis 를 쓴다.
 */
@SpringBootTest
class AdminCouponCampaignFacadeTest {

    @Autowired
    private AdminCouponCampaignFacade adminCouponCampaignFacade;

    @Autowired
    private CouponIssueFacade couponIssueFacade;

    @Autowired
    private CouponIssueTicketRepository couponIssueTicketRepository;

    @Autowired
    private CouponAdmissionSwitch admissionSwitch;

    @Autowired
    private CouponCampaigns couponCampaigns;

    @Autowired
    private CouponAdmissionState admissionState;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        couponCampaigns.clear();
        databaseCleanUp.truncateAllTables();
    }

    private void recordTicket(Long campaignId, long userId) {
        couponIssueTicketRepository.save(CouponIssueTicket.record(campaignId, userId, ZonedDateTime.now()));
    }

    private int issuedCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select issued_count from coupon_campaigns where id = ?", Integer.class, campaignId);
    }

    private long couponRowsOf(Long campaignId, long userId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from coupons where campaign_id = ? and user_id = ?", Long.class, campaignId, userId);
    }

    @DisplayName("수용을 다시 열 때,")
    @Nested
    class Open {
        @DisplayName("DB 의 쿠폰과 당첨 기록으로 당첨자 명단을 다시 만들고, Redis 에만 있던 처리 중 당첨은 지운다.")
        @Test
        void open_rebuildsWinnersFromCouponsAndTickets() {
            // arrange
            Long campaignId = couponCampaigns.open(5);
            CouponIssueInfo issued = couponIssueFacade.issue(campaignId, 1L);
            recordTicket(campaignId, 2L);
            admissionState.winner(campaignId, 1L, "PENDING");
            admissionState.winner(campaignId, 3L, "PENDING");
            admissionSwitch.close();

            // act
            adminCouponCampaignFacade.open(campaignId);

            // assert
            assertThat(admissionState.winnerOf(campaignId, 1L)).isEqualTo(String.valueOf(issued.couponId()));
            assertThat(admissionState.winnerOf(campaignId, 2L)).isEqualTo("TICKETED");
            assertThat(admissionState.winnerOf(campaignId, 3L)).isNull();
            assertThat(admissionState.metaOf(campaignId, "open")).isEqualTo("1");
            assertThat(admissionSwitch.isOpen()).isTrue();
        }
    }

    @DisplayName("캠페인을 종료할 때,")
    @Nested
    class Close {
        @DisplayName("새 사용자는 409 로 거절하고, 이미 받은 사용자에게는 같은 쿠폰을 돌려준다.")
        @Test
        void close_rejectsNewUser_butReturnsExistingCoupon() {
            // arrange
            Long campaignId = couponCampaigns.open(5);
            CouponIssueInfo issued = couponIssueFacade.issue(campaignId, 1L);

            // act
            adminCouponCampaignFacade.close(campaignId);

            // assert
            CoreException exception = assertThrows(CoreException.class, () -> couponIssueFacade.issue(campaignId, 2L));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.COUPON_CAMPAIGN_UNAVAILABLE);
            CouponIssueInfo repeat = couponIssueFacade.issue(campaignId, 1L);
            assertThat(repeat.status()).isEqualTo(Status.EXISTING);
            assertThat(repeat.couponId()).isEqualTo(issued.couponId());
        }
    }

    @DisplayName("마감 후 보정할 때,")
    @Nested
    class Reconcile {
        @DisplayName("202 로 약속한 당첨자의 쿠폰을 저장하고 당첨자 명단에 couponId 를 기록한다.")
        @Test
        void reconcile_issuesTicketedWinners() {
            // arrange
            Long campaignId = couponCampaigns.open(5);
            recordTicket(campaignId, 1L);
            admissionState.winner(campaignId, 1L, "TICKETED");

            // act
            CouponReconcileInfo result = adminCouponCampaignFacade.reconcile(campaignId);

            // assert
            assertThat(result.issued()).isEqualTo(1);
            assertThat(result.remaining()).isZero();
            assertThat(couponRowsOf(campaignId, 1L)).isEqualTo(1);
            assertThat(issuedCount(campaignId)).isEqualTo(1);
            assertThat(admissionState.winnerOf(campaignId, 1L)).matches("\\d+");
        }
    }
}
