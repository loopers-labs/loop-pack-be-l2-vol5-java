package com.loopers.application.coupon;

import com.loopers.application.coupon.dto.CouponIssueInfo;
import com.loopers.application.coupon.dto.CouponIssueInfo.Status;
import com.loopers.support.concurrent.ConcurrentRequests;
import com.loopers.support.concurrent.ConcurrentRequests.Results;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * 발급 Facade 가 Redis 판정과 DB 저장을 이어, 정상·재요청·동시 요청·DB 실패·품절에서 약속한 결과를 내는지 확인한다.
 * 실제 MySQL·Redis 를 쓴다. 자리 대기와 Redis 장애는 수용량·장애 검증 테스트에서 다룬다.
 */
@SpringBootTest
class CouponIssueFacadeTest {

    @Autowired
    private CouponIssueFacade couponIssueFacade;

    @MockitoSpyBean
    private CouponIssueWriter couponIssueWriter;

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

    private int issuedCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select issued_count from coupon_campaigns where id = ?", Integer.class, campaignId);
    }

    private long couponRows(Long campaignId) {
        return jdbcTemplate.queryForObject("select count(*) from coupons where campaign_id = ?", Long.class, campaignId);
    }

    private long ticketRows(Long campaignId) {
        return jdbcTemplate.queryForObject("select count(*) from coupon_issue_tickets where campaign_id = ?", Long.class, campaignId);
    }

    @DisplayName("쿠폰 발급을 요청할 때,")
    @Nested
    class Issue {
        @DisplayName("처음 요청하면 발급되고, 다시 요청하면 추가 발급 없이 같은 couponId 를 돌려준다.")
        @Test
        void issue_createsCoupon_thenRepeatReturnsSameCouponWithoutIssuing() {
            // arrange
            Long campaignId = couponCampaigns.open(5);

            // act
            CouponIssueInfo first = couponIssueFacade.issue(campaignId, 1L);
            CouponIssueInfo repeat = couponIssueFacade.issue(campaignId, 1L);

            // assert
            assertThat(first.status()).isEqualTo(Status.ISSUED);
            assertThat(repeat.status()).isEqualTo(Status.EXISTING);
            assertThat(repeat.couponId()).isEqualTo(first.couponId());
            assertThat(couponRows(campaignId)).isEqualTo(1);
            assertThat(issuedCount(campaignId)).isEqualTo(1);
        }

        @DisplayName("같은 사용자의 요청 5개가 동시에 와도 쿠폰은 한 장이고, 나머지는 처리 중 거절이거나 같은 쿠폰이다.")
        @Test
        void issue_createsOneCoupon_whenSameUserRequestsConcurrently() {
            // arrange
            Long campaignId = couponCampaigns.open(5);
            Queue<CouponIssueInfo> infos = new ConcurrentLinkedQueue<>();
            List<Runnable> requests = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                requests.add(() -> infos.add(couponIssueFacade.issue(campaignId, 1L)));
            }

            // act
            Results results = ConcurrentRequests.run(requests);

            // assert
            assertThat(results.technicalErrors()).isZero();
            assertThat(infos).filteredOn(info -> info.status() == Status.ISSUED).hasSize(1);
            Long couponId = infos.stream().filter(info -> info.status() == Status.ISSUED).findFirst().orElseThrow().couponId();
            assertThat(infos).filteredOn(info -> info.status() == Status.EXISTING)
                .allSatisfy(info -> assertThat(info.couponId()).isEqualTo(couponId));
            assertThat(infos.size() + results.rejectedWith(ErrorType.COUPON_ISSUE_IN_PROGRESS)).isEqualTo(5);
            assertThat(couponRows(campaignId)).isEqualTo(1);
            assertThat(issuedCount(campaignId)).isEqualTo(1);
        }

        @DisplayName("DB 연결에 실패하면 당첨 기록을 남기고 202(PENDING)로 약속하며, 자리는 반환한다.")
        @Test
        void issue_recordsTicketAndReturnsAccepted_whenDbTxFails() {
            // arrange
            Long campaignId = couponCampaigns.open(5);
            doThrow(new CannotCreateTransactionException("연결 획득 실패"))
                .when(couponIssueWriter).write(anyLong(), anyLong());

            // act
            CouponIssueInfo info = couponIssueFacade.issue(campaignId, 1L);

            // assert
            assertThat(info.status()).isEqualTo(Status.PENDING);
            assertThat(ticketRows(campaignId)).isEqualTo(1);
            assertThat(admissionState.winnerOf(campaignId, 1L)).isEqualTo("TICKETED");
            assertThat(admissionState.runningTokens(campaignId)).isEmpty();
            assertThat(couponRows(campaignId)).isZero();
        }

        @DisplayName("한도가 다 찬 뒤 새 사용자는 자리가 비어 있어도 품절로 거절한다.")
        @Test
        void issue_rejectsNewUser_whenSoldOutEvenIfSlotsAreFree() {
            // arrange
            Long campaignId = couponCampaigns.open(1);
            couponIssueFacade.issue(campaignId, 1L);

            // act
            CoreException exception = assertThrows(CoreException.class, () -> couponIssueFacade.issue(campaignId, 2L));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.COUPON_SOLD_OUT);
            assertThat(admissionState.runningTokens(campaignId)).isEmpty();
            assertThat(couponRows(campaignId)).isEqualTo(1);
        }
    }
}
