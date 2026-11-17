package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.coupon.CouponAdmissionService;
import com.loopers.application.coupon.CouponIssuanceWorker;
import com.loopers.application.coupon.CouponIssueSemaphore;
import com.loopers.application.coupon.CouponSelectionStore;
import com.loopers.config.redis.RedisConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.awaitility.Awaitility.await;

@Testcontainers
@DirtiesContext
@ActiveProfiles({"test", "coupon-admission-http-test"})
@SpringBootTest(classes = CouponHttpIntegrationFixture.IsolatedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false", "management.health.redis.enabled=false",
        "commerce.coupon.worker.enabled=true", "commerce.coupon.worker.capacity=1",
        "commerce.coupon.worker.campaign-ids=1099,2100", "commerce.coupon.worker.poll-delay=50ms"
    })
@Timeout(75)
class CouponRedisOutageRecoveryHttpIntegrationTest extends CouponHttpIntegrationFixture {
    private static final long IN_FLIGHT_CAMPAIGN = 2100;
    private static final String TOKENS = "commerce:coupon-issue:v1:{global}:tokens";
    @Autowired private CouponAdmissionService admission;
    @Autowired private CouponIssuanceWorker worker;
    @Autowired private CouponIssueSemaphore slots;
    @Autowired private CouponSelectionStore selection;
    @Autowired private CouponHttpDatabaseProbe database;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> master;

    @Test
    void aConnectedButUnresponsiveRedisReturns503BeforeTheExistingHttpClientTimesOut() throws Exception {
        long campaign = 2111;
        var observer = new JdbcTemplate(ownedDataSource());
        insertCampaign(observer, campaign, 2);
        admission.open(campaign);
        assertThat(selection.isOpen(campaign)).isTrue();
        int port = REDIS.actualPublishedPort();
        String id = REDIS.getContainerId();
        var docker = REDIS.getDockerClient();
        pingOwnedRedis(port);
        docker.pauseContainerCmd(id).exec();
        try {
            assertThat(docker.inspectContainerCmd(id).exec().getState().getPaused()).isTrue();
            assertThatIOException().isThrownBy(() -> pingOwnedRedis(port));
            assertUnavailable(apply(campaign, 13));
            assertThat(selection.isOpen(campaign)).isFalse();
            assertCampaignCounts(observer, campaign, 0, 0);
            assertThat(observer.queryForObject("SELECT COUNT(*) FROM coupon_reservation WHERE campaign_id=?",
                Integer.class, campaign)).isZero();
        } finally {
            docker.unpauseContainerCmd(id).exec();
        }
        pingOwnedRedis(port);
        // The buffered selection may have reached Redis. Its response did not authorize a MySQL reservation.
    }

    @Test
    void stoppedRedisRejectsNewApplicationsAndExplicitResumeIssuesTheCommittedReservationExactlyOnce() throws Exception {
        var observer = new JdbcTemplate(ownedDataSource());
        observer.update("UPDATE coupon_campaign SET total_quota=4 WHERE id=?", SCHEDULED_CAMPAIGN);
        insertCampaign(observer, IN_FLIGHT_CAMPAIGN, 1);
        admission.open(SCHEDULED_CAMPAIGN);
        admission.open(IN_FLIGHT_CAMPAIGN);

        long alreadyIssued = applyOnce(SCHEDULED_CAMPAIGN, 1);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(coupons(observer, alreadyIssued)).hasSize(1);
            assertThat(master.opsForSet().size(TOKENS)).isZero();
        });
        long existingCoupon = coupons(observer, alreadyIssued).getFirst();

        // A separate campaign lets #4 commit its reservation while #2 holds the sole issuance slot and a DB lock.
        var commit = database.blockNextIssuanceCommit();
        String containerId = REDIS.getContainerId();
        int port = REDIS.actualPublishedPort();
        var docker = REDIS.getDockerClient();
        long waiting;
        long inFlight;
        long inFlightCoupon;
        Set<String> heldTokens;
        try {
            inFlight = applyOnce(IN_FLIGHT_CAMPAIGN, 2);
            assertThat(commit.entered.await(8, TimeUnit.SECONDS)).isTrue();
            waiting = applyOnce(SCHEDULED_CAMPAIGN, 4);
            assertWaiting(observer, waiting);
            heldTokens = master.opsForSet().members(TOKENS);
            assertThat(heldTokens).hasSize(1);
            assertThat(selection.isOpen(SCHEDULED_CAMPAIGN)).isTrue();
            assertThat(slots.isOpen()).isTrue();

            // Keep the taken slot across this process restart; this scenario exercises return/reopen, not key loss.
            assertThat(REDIS.execInContainer("redis-cli", "SAVE").getStdout().trim()).isEqualTo("OK");
            docker.stopContainerCmd(containerId).withTimeout(3).exec();
            try {
                assertThat(docker.inspectContainerCmd(containerId).exec().getState().getRunning()).isFalse();
                assertThatIOException().isThrownBy(() -> pingOwnedRedis(port));
                commit.release.countDown();
                assertUnavailable(apply(SCHEDULED_CAMPAIGN, 10));
                assertThat(selection.isOpen(SCHEDULED_CAMPAIGN)).isFalse();
                await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                    assertThat(slots.isOpen()).as("issuance commit succeeded but token return failed").isFalse());
                assertThat(coupons(observer, inFlight)).hasSize(1);
                inFlightCoupon = coupons(observer, inFlight).getFirst();
                assertWaiting(observer, waiting);
                assertThat(userReservations(observer, 10)).isZero();
                assertCampaignCounts(observer, SCHEDULED_CAMPAIGN, 1, 1);
                assertCampaignCounts(observer, IN_FLIGHT_CAMPAIGN, 0, 1);

                var repeated = apply(SCHEDULED_CAMPAIGN, 1);
                assertThat(repeated.getStatusCode().value()).as("committed coupon readable during Redis outage").isEqualTo(200);
                assertThat(repeated.getBody().at("/data/status").asText()).isEqualTo("ISSUED");
                assertThat(repeated.getBody().at("/data/reservationId").longValue()).isEqualTo(alreadyIssued);
                assertThat(repeated.getBody().at("/data/couponId").longValue()).isEqualTo(existingCoupon);
            } finally {
                docker.startContainerCmd(containerId).exec();
            }
        } finally {
            commit.release.countDown();
        }

        assertThat(REDIS.actualPublishedPort()).isEqualTo(port);
        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().untilAsserted(() -> {
            try (var connection = master.getConnectionFactory().getConnection()) {
                assertThat(connection.ping()).isEqualTo("PONG");
            }
        });
        assertThat(selection.isOpen(SCHEDULED_CAMPAIGN)).isFalse();
        assertThat(slots.isOpen()).isFalse();
        assertThat(master.opsForSet().members(TOKENS)).isEqualTo(heldTokens);
        worker.poll(List.of(SCHEDULED_CAMPAIGN));
        assertUnavailable(apply(SCHEDULED_CAMPAIGN, 11));
        assertWaiting(observer, waiting);
        assertThat(userReservations(observer, 11)).isZero();

        admission.open(SCHEDULED_CAMPAIGN);
        admission.resumePending(SCHEDULED_CAMPAIGN);
        worker.open();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(coupons(observer, waiting)).hasSize(1);
            assertThat(master.opsForSet().size(TOKENS)).isZero();
        });
        long issuedToFourth = coupons(observer, waiting).getFirst();
        // Repeat the actual recovery paths; #4 submitted exactly once above.
        admission.resumePending(SCHEDULED_CAMPAIGN);
        worker.poll(List.of(SCHEDULED_CAMPAIGN, IN_FLIGHT_CAMPAIGN));
        assertThat(userReservations(observer, 4)).isOne();
        assertThat(status(observer, waiting)).isEqualTo("ISSUED");
        assertThat(coupons(observer, waiting)).containsExactly(issuedToFourth);
        assertThat(coupons(observer, alreadyIssued)).containsExactly(existingCoupon);
        assertThat(coupons(observer, inFlight)).containsExactly(inFlightCoupon);
        assertThat(userReservations(observer, 10)).isZero();
        assertThat(userReservations(observer, 11)).isZero();
        assertCampaignCounts(observer, SCHEDULED_CAMPAIGN, 0, 2);
        assertCampaignCounts(observer, IN_FLIGHT_CAMPAIGN, 0, 1);

        long fresh = applyOnce(SCHEDULED_CAMPAIGN, 12);
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(coupons(observer, fresh)).hasSize(1);
            assertThat(master.opsForSet().size(TOKENS)).isZero();
        });
        assertCampaignCounts(observer, SCHEDULED_CAMPAIGN, 0, 3);
        assertThat(coupons(observer, waiting)).containsExactly(issuedToFourth);
    }

    private long applyOnce(long campaign, long user) {
        var response = apply(campaign, user);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().at("/data/status").asText()).isEqualTo("WAITING");
        return response.getBody().at("/data/reservationId").longValue();
    }

    private void assertUnavailable(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().at("/meta/result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().at("/meta/errorCode").asText()).isEqualTo("COUPON_ADMISSION_UNAVAILABLE");
        assertThat(response.getBody().has("data")).isFalse();
    }

    private void assertWaiting(JdbcTemplate observer, long reservation) {
        assertThat(status(observer, reservation)).isEqualTo("WAITING");
        assertThat(coupons(observer, reservation)).isEmpty();
    }

    private String status(JdbcTemplate observer, long reservation) {
        return observer.queryForObject("SELECT status FROM coupon_reservation WHERE id=?", String.class, reservation);
    }

    private List<Long> coupons(JdbcTemplate observer, long reservation) {
        return observer.queryForList("SELECT id FROM owned_coupon WHERE reservation_id=?", Long.class, reservation);
    }

    private int userReservations(JdbcTemplate observer, long user) {
        return observer.queryForObject("SELECT COUNT(*) FROM coupon_reservation WHERE campaign_id=? AND user_id=?",
            Integer.class, SCHEDULED_CAMPAIGN, user);
    }

    private void assertCampaignCounts(JdbcTemplate observer, long campaign, int waiting, int issued) {
        var counts = observer.queryForMap("SELECT waiting_count, issued_count FROM coupon_campaign WHERE id=?", campaign);
        assertThat(counts).containsEntry("waiting_count", waiting).containsEntry("issued_count", issued);
    }
}
