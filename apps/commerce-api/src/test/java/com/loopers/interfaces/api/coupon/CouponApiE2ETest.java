package com.loopers.interfaces.api.coupon;

import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.support.fixture.CouponCampaigns;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponApiE2ETest {

    private static final String COUPONS_URL = "/api/v1/coupons";
    private static final String USER_ID_HEADER = "X-USER-ID";

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CouponCampaigns couponCampaigns;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        couponCampaigns.clear();
        databaseCleanUp.truncateAllTables();
    }

    private String saveUser() {
        return String.valueOf(userRepository.save(User.create()).getId());
    }

    private HttpHeaders headers(String userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set(USER_ID_HEADER, userId);
        }
        return headers;
    }

    private static String issueUrl(Long campaignId) {
        return "/api/v1/coupon-campaigns/" + campaignId + "/issues";
    }

    private ResponseEntity<ApiResponse<CouponDto.IssueResponse>> issue(Long campaignId, String userId) {
        return testRestTemplate.exchange(
            issueUrl(campaignId), HttpMethod.POST, new HttpEntity<>(headers(userId)),
            new ParameterizedTypeReference<>() {}
        );
    }

    private ResponseEntity<ApiResponse<PageResponse<CouponDto.CouponResponse>>> myCoupons(String userId) {
        return testRestTemplate.exchange(
            COUPONS_URL, HttpMethod.GET, new HttpEntity<>(headers(userId)),
            new ParameterizedTypeReference<>() {}
        );
    }

    @DisplayName("POST /api/v1/coupon-campaigns/{campaignId}/issues")
    @Nested
    class Issue {
        @DisplayName("처음 발급하면 201 과 couponId 를 주고, 그 쿠폰은 본인 목록에만 보인다.")
        @Test
        void issue_returns201_andOnlyOwnerSeesCouponInList() {
            // arrange
            Long campaignId = couponCampaigns.open(5);
            String owner = saveUser();
            String other = saveUser();

            // act
            ResponseEntity<ApiResponse<CouponDto.IssueResponse>> response = issue(campaignId, owner);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Long couponId = response.getBody().data().couponId();
            assertThat(couponId).isNotNull();
            assertThat(myCoupons(owner).getBody().data().content())
                .extracting(CouponDto.CouponResponse::couponId).containsExactly(couponId);
            assertThat(myCoupons(other).getBody().data().content()).isEmpty();
        }

        @DisplayName("한도가 다 찬 뒤 다른 사용자가 요청하면 409 COUPON_SOLD_OUT 으로 응답한다.")
        @Test
        void issue_returns409_whenSoldOut() {
            // arrange
            Long campaignId = couponCampaigns.open(1);
            issue(campaignId, saveUser());

            // act
            ResponseEntity<ApiResponse<CouponDto.IssueResponse>> response = issue(campaignId, saveUser());

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().meta().errorCode()).isEqualTo("COUPON_SOLD_OUT");
        }
    }

    @DisplayName("요청자 식별이 필요한 쿠폰 API 는,")
    @Nested
    class Identification {
        @DisplayName("X-USER-ID 헤더가 없으면 400 으로 거절한다.")
        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
            "POST, /api/v1/coupon-campaigns/1/issues",
            "GET, /api/v1/coupons"
        })
        void returns400_whenUserHeaderIsMissing(String method, String url) {
            // act
            ResponseEntity<ApiResponse<Object>> response = testRestTemplate.exchange(
                url, HttpMethod.valueOf(method), new HttpEntity<>(headers(null)),
                new ParameterizedTypeReference<>() {}
            );

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }
}
