package com.loopers.domains.coupon.in;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domains.coupon.app.CouponIssueService;
import com.loopers.domains.coupon.app.port.CouponRepository;
import com.loopers.domains.coupon.app.port.DuplicateIssueException;
import com.loopers.domains.coupon.domain.CouponCampaign;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 8주차 29절: DB만으로 한정 수량 쿠폰을 발급한다. 실제 Controller~MySQL로 보고, 실패 주입은 테스트 설정의 포트 대역만 쓴다.
class CouponIssueApiIntegrationTest extends CouponIntegrationSupport {
    @Autowired private CouponIssueService couponIssueService;
    @Autowired private CouponRepository couponRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("T1: 사용자 A가 발급하면 201과 쿠폰 1개·발급 수 1이 남고, A 목록에는 같은 couponId가 있고 B 목록은 비어 있다")
    void firstIssueCreatesCouponVisibleOnlyToOwner() throws Exception {
        user(1);
        user(2);
        long campaignId = campaign(true, 10, 0, 3000);

        JsonNode issued = body(issue(1, campaignId).andExpect(status().isCreated()));

        long couponId = issued.path("data").path("couponId").asLong();
        assertThat(couponId).isEqualTo(couponId(campaignId, 1));
        assertThat(issued.path("data").path("campaignId").asLong()).isEqualTo(campaignId);
        assertThat(issued.path("data").path("discountAmount").asLong()).isEqualTo(3000);
        assertThat(coupons(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        mine(1).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].couponId").value(couponId));
        mine(2).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("T2: 같은 사용자가 두 번 요청하면 두 번째는 200과 같은 couponId이고 쿠폰·발급 수는 1 그대로다")
    void secondRequestReturnsSameCoupon() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);

        long first = body(issue(1, campaignId).andExpect(status().isCreated())).path("data").path("couponId").asLong();
        long second = body(issue(1, campaignId).andExpect(status().isOk())).path("data").path("couponId").asLong();

        assertThat(second).isEqualTo(first);
        assertThat(coupons(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @Test
    @DisplayName("T5: 발급 수를 올린 뒤 쿠폰 저장이 실패하면 오류가 그대로 나가고 같은 트랜잭션이 rollback되어 발급 수 0·쿠폰 0이다")
    void insertFailureRollsBackIssuedCount() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);
        failingCoupons.failNextInsertWith(new IllegalStateException("주입된 쿠폰 저장 실패"));

        issue(1, campaignId).andExpect(status().is5xxServerError());

        assertThat(issuedCount(campaignId)).isZero();
        assertThat(coupons(campaignId)).isZero();
    }

    @Test
    @DisplayName("T6: 서비스로 발급하고 결과를 버린 뒤 API로 다시 요청하면 200과 그 쿠폰의 couponId이고 추가 발급은 없다")
    void requestAfterLostResponseReturnsExistingCoupon() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);
        couponIssueService.issue(campaignId, 1);

        long couponId = body(issue(1, campaignId).andExpect(status().isOk())).path("data").path("couponId").asLong();

        assertThat(couponId).isEqualTo(couponId(campaignId, 1));
        assertThat(coupons(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @Test
    @DisplayName("T7: 없는 캠페인에 요청하면 404이고 쿠폰 테이블은 바뀌지 않는다")
    void missingCampaignIsNotFound() throws Exception {
        user(1);

        issue(1, 999_999).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from issued_coupon", Long.class)).isZero();
    }

    @Test
    @DisplayName("T8: 비활성 캠페인과 발급 수가 총량에 닿은 캠페인은 둘 다 409이고 발급 수·쿠폰은 바뀌지 않는다")
    void inactiveAndSoldOutAreConflict() throws Exception {
        user(1);
        long inactive = campaign(false, 10, 0, 3000);
        long soldOut = campaign(true, 3, 3, 3000);

        issue(1, inactive).andExpect(status().isConflict());
        issue(1, soldOut).andExpect(status().isConflict());

        assertThat(issuedCount(inactive)).isZero();
        assertThat(issuedCount(soldOut)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from issued_coupon", Long.class)).isZero();
    }

    @Test
    @DisplayName("T9: 발급받은 뒤 캠페인이 비활성·품절이 되어도 다시 요청하면 200과 같은 couponId이고 목록에도 남아 있다")
    void ownerStillGetsCouponAfterCampaignEnds() throws Exception {
        user(1);
        long campaignId = campaign(true, 1, 0, 3000);
        long couponId = body(issue(1, campaignId).andExpect(status().isCreated())).path("data").path("couponId").asLong();
        jdbc.update("update coupon_campaign set active = false where id = ?", campaignId);

        long again = body(issue(1, campaignId).andExpect(status().isOk())).path("data").path("couponId").asLong();

        assertThat(again).isEqualTo(couponId);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        mine(1).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].couponId").value(couponId));
    }

    @Test
    @DisplayName("T10: ①을 거치지 않고 조건부 UPDATE만 부르면 발급 수가 총량−1일 때 1행, 총량일 때 0행이고 ①의 발급 가능 판정 경계와 같다")
    void conditionalUpdateMatchesLookupBoundary() {
        long campaignId = campaign(true, 3, 2, 3000);

        Integer belowQuota = transactionTemplate.execute(status -> couponRepository.increaseIssued(campaignId));
        Integer atQuota = transactionTemplate.execute(status -> couponRepository.increaseIssued(campaignId));

        assertThat(belowQuota).isEqualTo(1);
        assertThat(atQuota).isZero();
        assertThat(issuedCount(campaignId)).isEqualTo(3);
        assertThat(new CouponCampaign(campaignId, "c", true, 3, 2, 3000).canIssue()).isTrue();
        assertThat(new CouponCampaign(campaignId, "c", true, 3, 3, 3000).canIssue()).isFalse();
    }

    @Test
    @DisplayName("T11a: 쿠폰 저장이 유일 제약이 아닌 이유(DB 접근 실패)로 실패하면 200으로 바꾸지 않고 오류가 그대로 나가며 발급 수 0·쿠폰 0이다")
    void nonDuplicateInsertFailureIsNotTurnedIntoSuccess() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);
        failingCoupons.failNextInsertWith(new DataAccessResourceFailureException("주입된 DB 접근 실패"));

        issue(1, campaignId).andExpect(status().is5xxServerError());

        assertThat(issuedCount(campaignId)).isZero();
        assertThat(coupons(campaignId)).isZero();
    }

    @Test
    @DisplayName("T11b: 유일 제약 충돌인데 다시 조회해도 쿠폰이 없으면 200으로 바꾸지 않고 원래 오류가 그대로 나가며 발급 수 0이다")
    void duplicateWithoutExistingCouponIsNotTurnedIntoSuccess() throws Exception {
        user(1);
        long campaignId = campaign(true, 10, 0, 3000);
        failingCoupons.failNextInsertWith(new DuplicateIssueException("주입된 유일 제약 충돌", null));

        issue(1, campaignId).andExpect(status().is5xxServerError());

        assertThat(issuedCount(campaignId)).isZero();
        assertThat(coupons(campaignId)).isZero();
    }

    @Test
    @DisplayName("T12: 여러 캠페인에서 발급받으면 목록은 본인 것만 발급 시각 내림차순이고 같은 시각이면 couponId 내림차순이다")
    void listIsOwnCouponsNewestFirst() throws Exception {
        user(1);
        user(2);
        long first = campaign(true, 10, 0, 1000);
        long second = campaign(true, 10, 0, 2000);
        long third = campaign(true, 10, 0, 3000);
        issue(1, first).andExpect(status().isCreated());
        issue(2, first).andExpect(status().isCreated());
        clock.advance(Duration.ofSeconds(1));
        issue(1, second).andExpect(status().isCreated());
        issue(1, third).andExpect(status().isCreated());

        JsonNode list = body(mine(1).andExpect(status().isOk())).path("data");

        List<Long> ids = new ArrayList<Long>();
        list.forEach(coupon -> ids.add(coupon.path("couponId").asLong()));
        assertThat(ids).containsExactly(couponId(third, 1), couponId(second, 1), couponId(first, 1));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
