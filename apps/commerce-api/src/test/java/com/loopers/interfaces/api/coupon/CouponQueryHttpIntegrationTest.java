package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.coupon.CouponAdmissionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@DirtiesContext
@ActiveProfiles({"test", "coupon-admission-http-test"})
@SpringBootTest(classes = CouponHttpIntegrationFixture.IsolatedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "management.health.redis.enabled=false",
        "commerce.coupon.worker.enabled=true", "commerce.coupon.worker.campaign-ids=1099",
        "commerce.coupon.worker.poll-delay=50ms"
    })
@Timeout(60)
class CouponQueryHttpIntegrationTest extends CouponHttpIntegrationFixture {
    private static final String CAMPAIGN_STATUS = "/api/v1/coupon-campaigns/" + SCHEDULED_CAMPAIGN + "/issues/me";
    private static final String MY_COUPONS = "/api/v1/coupons";
    @Autowired private CouponHttpDatabaseProbe applicationDatabase;
    @Autowired private CouponAdmissionService admission;

    @Test
    void reopeningShowsOnlyCommittedPersonalResultsAndNeverRequiresAnotherApplication() throws Exception {
        var observer = new JdbcTemplate(ownedDataSource());
        observer.update("UPDATE coupon_campaign SET total_quota=2 WHERE id=?", SCHEDULED_CAMPAIGN);
        admission.open(SCHEDULED_CAMPAIGN);
        long otherReservation = applyOnce(2);
        await().atMost(Duration.ofSeconds(8)).until(() -> issuedIds(observer, otherReservation).size() == 1);
        long otherCoupon = issuedIds(observer, otherReservation).getFirst();

        var commit = applicationDatabase.blockNextIssuanceCommit();
        long reservation;
        try {
            reservation = applyOnce(1);
            assertThat(commit.entered.await(8, TimeUnit.SECONDS)).as("issuance writes still uncommitted").isTrue();
            var waiting = read(1, CAMPAIGN_STATUS);
            assertThat(waiting.path("reservationId").longValue()).isEqualTo(reservation);
            assertThat(waiting.path("campaignId").longValue()).isEqualTo(SCHEDULED_CAMPAIGN);
            assertThat(waiting.path("status").asText()).isEqualTo("WAITING");
            assertThat(waiting.has("couponId")).isFalse();
            assertThat(couponIds(1)).isEmpty();
            assertThat(couponIds(2)).containsExactly(otherCoupon);
            assertThat(read(2, CAMPAIGN_STATUS).path("reservationId").longValue()).isEqualTo(otherReservation);
        } finally {
            commit.release.countDown();
        }

        await().atMost(Duration.ofSeconds(8)).until(() -> issuedIds(observer, reservation).size() == 1);
        long coupon = issuedIds(observer, reservation).getFirst();
        var issued = read(1, CAMPAIGN_STATUS);
        assertThat(issued.path("status").asText()).isEqualTo("ISSUED");
        assertThat(issued.path("reservationId").longValue()).isEqualTo(reservation);
        assertThat(issued.path("couponId").longValue()).isEqualTo(coupon);
        assertThat(couponIds(1)).containsExactly(coupon);
        assertThat(couponIds(2)).containsExactly(otherCoupon);
        assertThat(couponIds(3)).isEmpty();
        assertThat(observer.queryForObject("SELECT COUNT(*) FROM coupon_reservation WHERE campaign_id=?", Integer.class,
            SCHEDULED_CAMPAIGN)).isEqualTo(2);
        assertThat(observer.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id=?", Integer.class,
            SCHEDULED_CAMPAIGN)).isEqualTo(2);
    }

    private long applyOnce(long userId) {
        var response = apply(SCHEDULED_CAMPAIGN, userId);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().at("/data/status").asText()).isEqualTo("WAITING");
        return response.getBody().at("/data/reservationId").longValue();
    }

    private JsonNode read(long userId, String path) {
        var headers = new HttpHeaders();
        headers.set("X-USER-ID", "applicant-" + userId);
        var response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).as("GET %s as applicant-%s", path, userId).isEqualTo(200);
        assertThat(response.getBody().at("/meta/result").asText()).isEqualTo("SUCCESS");
        return response.getBody().path("data");
    }

    private List<Long> couponIds(long userId) {
        JsonNode items = read(userId, MY_COUPONS).path("items");
        assertThat(items.isArray()).isTrue();
        return items.findValues("couponId").stream().map(JsonNode::longValue).toList();
    }

    private List<Long> issuedIds(JdbcTemplate observer, long reservation) {
        return observer.queryForList("SELECT id FROM owned_coupon WHERE reservation_id=?", Long.class, reservation);
    }
}
