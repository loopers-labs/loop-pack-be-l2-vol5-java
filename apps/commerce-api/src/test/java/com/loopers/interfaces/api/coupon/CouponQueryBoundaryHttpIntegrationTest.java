package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.coupon.CouponIssuanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

@Testcontainers
@DirtiesContext
@ActiveProfiles({"test", "coupon-admission-http-test"})
@SpringBootTest(classes = CouponHttpIntegrationFixture.IsolatedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "management.health.redis.enabled=false",
        "commerce.coupon.admission.enabled=false", "commerce.coupon.worker.enabled=false"
    })
@Timeout(45)
class CouponQueryBoundaryHttpIntegrationTest extends CouponHttpIntegrationFixture {
    private static final String COUPONS = "/api/v1/coupons";
    private static final String STATE = "/api/v1/coupon-campaigns/" + SCHEDULED_CAMPAIGN + "/issues/me";
    @Autowired private CouponIssuanceService issuance;

    @ParameterizedTest
    @ValueSource(strings = {STATE, COUPONS})
    void requiresTheExistingCustomerIdentityForBothReadEndpoints(String path) {
        assertError(get(path, null), 400, "INVALID_REQUEST");
        assertError(get(path, "unregistered-customer"), 404, "USER_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {STATE, COUPONS})
    void aReopenedScreenFetchesPersonalStateWithoutReusingACachedResponse(String path) {
        var response = get(path, "applicant-10");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "/api/v1/coupon-campaigns/abc/issues/me", "/api/v1/coupon-campaigns/0/issues/me",
        COUPONS + "?page=-1", COUPONS + "?page=2147483648", COUPONS + "?size=0", COUPONS + "?size=101"
    })
    void invalidCampaignAndPagingInputsUseTheCommerceErrorContract(String path) {
        assertError(get(path, "applicant-1"), 400, "INVALID_REQUEST");
    }

    @Test
    void distinguishesAnExistingCampaignWithoutMyReservationFromAMissingCampaign() {
        var jdbc = new JdbcTemplate(ownedDataSource());
        long campaign = 8111;
        insertCampaign(jdbc, campaign, 1);
        issuance.reserve(campaign, 2);
        issuance.issue(campaign, 2);

        var state = data(get("/api/v1/coupon-campaigns/" + campaign + "/issues/me", "applicant-1"));
        assertThat(state.path("campaignId").asLong()).isEqualTo(campaign);
        assertThat(state.path("status").textValue()).isEqualTo("NOT_APPLIED");
        assertThat(state.has("reservationId")).isFalse();
        assertThat(state.has("couponId")).isFalse();
        assertError(get("/api/v1/coupon-campaigns/99999999/issues/me", "applicant-1"), 404, "COUPON_CAMPAIGN_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_reservation WHERE campaign_id=? AND user_id=1",
            Integer.class, campaign)).isZero();
    }

    @Test
    void countsOnlyMyIssuedCouponsAndKeepsTheTieBreakAcrossPageBoundaries() {
        var jdbc = new JdbcTemplate(ownedDataSource());
        List<Long> ids = List.of(8121L, 8122L, 8123L).stream().map(campaign -> {
            insertCampaign(jdbc, campaign, 2);
            issuance.reserve(campaign, 4);
            return issuance.issue(campaign, 4).couponId();
        }).toList();
        issuance.reserve(8121L, 5);
        issuance.issue(8121L, 5);
        insertCampaign(jdbc, 8124L, 1);
        issuance.reserve(8124L, 4);
        jdbc.update("""
            UPDATE owned_coupon SET issued_at =
              CASE WHEN id = ? THEN '2026-11-12 10:00:00' ELSE '2026-11-12 09:00:00' END
            WHERE id IN (?, ?, ?)
            """, ids.getFirst(), ids.getFirst(), ids.get(1), ids.getLast());

        var defaults = data(get(COUPONS, "applicant-4"));
        assertThat(data(get(COUPONS + "?userId=applicant-5", "applicant-4"))).isEqualTo(defaults);
        assertThat(defaults.path("page").intValue()).isZero();
        assertThat(defaults.path("size").intValue()).isEqualTo(20);
        assertThat(defaults.path("totalElements").longValue()).isEqualTo(3);
        assertThat(defaults.path("totalPages").intValue()).isOne();
        var first = data(get(COUPONS + "?size=2", "applicant-4"));
        assertThat(first.path("items")).hasSize(2);
        assertThat(first.at("/items/0/couponId").longValue()).isEqualTo(ids.getFirst());
        assertThat(first.at("/items/1/couponId").longValue()).isEqualTo(ids.getLast());
        var second = data(get(COUPONS + "?page=1&size=2", "applicant-4"));
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.at("/items/0/couponId").longValue()).isEqualTo(ids.get(1));
        var beyond = data(get(COUPONS + "?page=2147483647&size=2", "applicant-4"));
        assertThat(beyond.path("items").isArray()).isTrue();
        assertThat(beyond.path("items")).isEmpty();
        assertThat(beyond.path("page").intValue()).isEqualTo(Integer.MAX_VALUE);
        assertThat(beyond.path("size").intValue()).isEqualTo(2);
        assertThat(beyond.path("totalElements").longValue()).isEqualTo(3);
        assertThat(beyond.path("totalPages").intValue()).isEqualTo(2);
    }

    @Test
    void readsCommittedWaitingAndIssuedResultsWhileTheApplicationsOwnRedisEndpointIsPaused() throws IOException {
        var jdbc = new JdbcTemplate(ownedDataSource());
        long campaign = 8131L;
        insertCampaign(jdbc, campaign, 2);
        var waiting = issuance.reserve(campaign, 6);
        issuance.reserve(campaign, 7);
        long coupon = issuance.issue(campaign, 7).couponId();
        String path = "/api/v1/coupon-campaigns/" + campaign + "/issues/me";

        var docker = DockerClientFactory.instance().client();
        String containerId = REDIS.getContainerId();
        int port = REDIS.actualPublishedPort();
        pingOwnedRedis(port);
        docker.pauseContainerCmd(containerId).exec();
        try {
            assertThat(docker.inspectContainerCmd(containerId).exec().getState().getPaused()).isTrue();
            assertThatIOException().isThrownBy(() -> pingOwnedRedis(port));
            var pending = data(get(path, "applicant-6"));
            assertThat(pending.path("status").textValue()).isEqualTo("WAITING");
            assertThat(pending.path("reservationId").longValue()).isEqualTo(waiting.reservationId());
            assertThat(pending.has("couponId")).isFalse();
            assertThat(data(get(COUPONS, "applicant-6")).path("items")).isEmpty();

            var issued = data(get(path, "applicant-7"));
            assertThat(issued.path("status").textValue()).isEqualTo("ISSUED");
            assertThat(issued.path("couponId").longValue()).isEqualTo(coupon);
            var items = data(get(COUPONS, "applicant-7")).path("items");
            assertThat(items).hasSize(1);
            assertThat(items.get(0).path("couponId").longValue()).isEqualTo(coupon);
        } finally {
            docker.unpauseContainerCmd(containerId).exec();
        }
        pingOwnedRedis(port);
    }

    private JsonNode data(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().at("/meta/result").textValue()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private ResponseEntity<JsonNode> get(String path, String requester) {
        var headers = new HttpHeaders();
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        return http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    }

    private void assertError(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        JsonNode body = response.getBody();
        assertThat(body.at("/meta/result").asText()).isEqualTo("FAIL");
        assertThat(body.at("/meta/errorCode").asText()).isEqualTo(code);
        assertThat(body.has("data")).isFalse();
    }
}
