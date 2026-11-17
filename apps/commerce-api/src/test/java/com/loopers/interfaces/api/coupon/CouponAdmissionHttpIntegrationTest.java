package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.coupon.CouponAdmissionService;
import com.loopers.application.coupon.CouponIssuanceService;
import com.loopers.application.coupon.CouponSelectionStore;
import com.loopers.config.coupon.CouponSelectionRedisConfiguration;
import com.loopers.config.redis.RedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

@Testcontainers
@DirtiesContext
@ActiveProfiles({"test", "coupon-admission-http-test"})
@SpringBootTest(classes = CouponHttpIntegrationFixture.IsolatedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "management.health.redis.enabled=false",
        "commerce.coupon.admission.enabled=true", "commerce.coupon.admission.campaign-ids=1099",
        "commerce.coupon.admission.recovery-delay=100ms"
    })
@Timeout(90)
class CouponAdmissionHttpIntegrationTest extends CouponHttpIntegrationFixture {
    @Autowired private CouponAdmissionService admission;
    @Autowired private CouponIssuanceService issuance;
    @Autowired private CouponSelectionStore selection;
    @Autowired private ApplicationContext application;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> master;
    @Autowired private CouponHttpDatabaseProbe applicationDatabase;
    private JdbcTemplate observer;

    @BeforeEach
    void connectObserverDirectlyToTheIsolatedDatabase() {
        observer = new JdbcTemplate(ownedDataSource());
    }

    @Test
    void hundredAndFirstNewRequestIsRejectedBeforeAnyCouponDatabaseAccess() {
        long campaignId = 1001;
        prepareOpenCampaign(campaignId, 100);
        for (int user = 1; user <= 100; user++) {
            var response = apply(campaignId, user);
            assertThat(response.getStatusCode().value()).as("applicant %d", user).isEqualTo(202);
            assertThat(response.getBody().at("/data/status").asText()).isEqualTo("WAITING");
        }
        assertDatabase(campaignId, 100);
        long previousConnections = applicationDatabase.connections();
        var rejected = apply(campaignId, 101);
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(rejected.getBody().at("/meta/result").asText()).isEqualTo("FAIL");
        assertThat(rejected.getBody().at("/meta/errorCode").asText()).isEqualTo("COUPON_CAMPAIGN_SOLD_OUT");
        assertThat(rejected.getBody().has("data")).isFalse();
        assertThat(applicationDatabase.connections()).as("101st request opened no application DB connection")
            .isEqualTo(previousConnections);
        assertDatabase(campaignId, 100);
        assertThat(observer.queryForObject("SELECT COUNT(*) FROM coupon_reservation WHERE campaign_id=? AND user_id=101",
            Integer.class, campaignId)).isZero();

        var repeat = apply(campaignId, 100);
        assertThat(repeat.getStatusCode().value()).isEqualTo(202);
        long saved = observer.queryForObject("SELECT id FROM coupon_reservation WHERE campaign_id=? AND user_id=100",
            Long.class, campaignId);
        assertThat(repeat.getBody().at("/data/reservationId").asLong()).isEqualTo(saved);
        assertThat(applicationDatabase.connections()).as("a request that actually reads MySQL is observed")
            .isGreaterThan(previousConnections);
        assertDatabase(campaignId, 100);
    }

    @Test
    void http202WaitsForThePhysicalReservationCommit() throws Exception {
        long campaignId = 1002;
        prepareOpenCampaign(campaignId, 1);
        var gate = applicationDatabase.blockNextReservationCommit();
        var client = Executors.newSingleThreadExecutor();
        try {
            var response = client.submit(() -> apply(campaignId, 1));
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).as("actual JDBC commit reached").isTrue();
            assertDatabase(campaignId, 0);
            assertThatExceptionOfType(TimeoutException.class).isThrownBy(() -> response.get(200, TimeUnit.MILLISECONDS));
            gate.release.countDown();
            var accepted = response.get(10, TimeUnit.SECONDS);
            assertThat(accepted.getStatusCode().value()).isEqualTo(202);
            assertThat(accepted.getBody().at("/meta/result").asText()).isEqualTo("SUCCESS");
            assertThat(accepted.getBody().at("/data").size()).isEqualTo(3);
            assertThat(accepted.getBody().at("/data/campaignId").asLong()).isEqualTo(campaignId);
            assertThat(accepted.getBody().at("/data/status").asText()).isEqualTo("WAITING");
            assertThat(accepted.getBody().at("/data").has("couponId")).isFalse();
            var reservationId = observer.queryForObject(
                "SELECT id FROM coupon_reservation WHERE campaign_id=? AND user_id=1 AND status='WAITING'",
                Long.class, campaignId);
            assertThat(accepted.getBody().at("/data/reservationId").asLong()).isEqualTo(reservationId);
            assertDatabase(campaignId, 1);
        } finally {
            gate.release.countDown();
            client.shutdownNow();
            assertThat(client.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void failedPhysicalCommitCannotReturn202AndItsWorkCanResumeWithoutAnotherPost() throws Exception {
        long campaignId = 1003;
        prepareOpenCampaign(campaignId, 1);
        var gate = applicationDatabase.blockNextReservationCommit();
        var client = Executors.newSingleThreadExecutor();
        try {
            var response = client.submit(() -> apply(campaignId, 7));
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertDatabase(campaignId, 0);
            gate.failAndRelease();
            var failed = response.get(10, TimeUnit.SECONDS);
            assertThat(failed.getStatusCode().value()).isEqualTo(503);
            assertThat(failed.getBody().at("/meta/result").asText()).isEqualTo("FAIL");
            assertThat(failed.getBody().at("/meta/errorCode").asText()).isEqualTo("COUPON_ADMISSION_UNAVAILABLE");
            assertThat(failed.getBody().has("data")).isFalse();
            assertDatabase(campaignId, 0);
        } finally {
            gate.release.countDown();
            client.shutdownNow();
            assertThat(client.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        admission.open(campaignId);
        assertThat(selection.pending(campaignId, 1)).containsExactly(7L);
        admission.resumePending(campaignId);
        assertDatabase(campaignId, 1);
        assertThat(selection.pending(campaignId, 1)).isEmpty();
    }

    @Test
    void lostCommitAcknowledgmentUsesTheSavedReservationBeforeReturning202() throws Exception {
        long campaignId = 1006;
        prepareOpenCampaign(campaignId, 1);
        var gate = applicationDatabase.blockNextReservationCommit();
        var client = Executors.newSingleThreadExecutor();
        try {
            var response = client.submit(() -> apply(campaignId, 9));
            assertThat(gate.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertDatabase(campaignId, 0);
            long connectionsBeforeCommit = applicationDatabase.connections();
            gate.commitAndLoseAcknowledgment();
            var accepted = response.get(10, TimeUnit.SECONDS);
            assertThat(accepted.getStatusCode().value()).isEqualTo(202);
            assertThat(accepted.getBody().at("/data/status").asText()).isEqualTo("WAITING");
            assertThat(applicationDatabase.connections()).as("verification opens a separate connection after COMMIT")
                .isGreaterThan(connectionsBeforeCommit);
            assertThat(accepted.getBody().at("/data/reservationId").asLong()).isEqualTo(observer.queryForObject(
                "SELECT id FROM coupon_reservation WHERE campaign_id=? AND user_id=9", Long.class, campaignId));
            assertDatabase(campaignId, 1);
            assertThat(selection.pending(campaignId, 1)).isEmpty();
        } finally {
            gate.release.countDown();
            client.shutdownNow();
            assertThat(client.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void recoveryUsesTheSavedRedisSelectionWhenProcessingStoppedBeforeMysql() {
        long campaignId = 1004;
        prepareOpenCampaign(campaignId, 1);
        // Stop the flow immediately after selection, before the request can call reserve().
        assertThat(selection.select(campaignId, 8)).isEqualTo(CouponSelectionStore.Selection.NEW);
        assertDatabase(campaignId, 0);
        // Replace the servlet application's selection adapter and orchestration instances. The databases survive.
        try (var restarted = new AnnotationConfigApplicationContext()) {
            restarted.setParent(application);
            restarted.registerBean("couponSelectionStore", CouponSelectionStore.class,
                () -> new CouponSelectionRedisConfiguration().couponSelectionStore(master));
            restarted.registerBean("couponAdmissionService", CouponAdmissionService.class);
            restarted.refresh();
            var recovery = restarted.getBean(CouponAdmissionService.class);
            var survivingWork = restarted.getBean(CouponSelectionStore.class);
            recovery.open(campaignId);
            assertThat(survivingWork.pending(campaignId, 1)).containsExactly(8L);
            recovery.resumePending(campaignId);
            assertDatabase(campaignId, 1);
            Long saved = observer.queryForObject(
                "SELECT id FROM coupon_reservation WHERE campaign_id=? AND user_id=8", Long.class, campaignId);
            assertThat(survivingWork.pending(campaignId, 1)).isEmpty();
            recovery.resumePending(campaignId);
            assertDatabase(campaignId, 1);
            assertThat(observer.queryForObject(
                "SELECT id FROM coupon_reservation WHERE campaign_id=? AND user_id=8", Long.class, campaignId)).isEqualTo(saved);
        }
    }

    @Test
    void reapplyingAfterIssueReturnsTheOriginalCouponIdAtCapacity() {
        long campaignId = 1005;
        prepareOpenCampaign(campaignId, 1);
        assertThat(apply(campaignId, 3).getStatusCode().value()).isEqualTo(202);
        var issued = issuance.issue(campaignId, 3);
        var repeat = apply(campaignId, 3);
        assertThat(repeat.getStatusCode().value()).isEqualTo(200);
        assertThat(repeat.getBody().at("/meta/result").asText()).isEqualTo("SUCCESS");
        assertThat(repeat.getBody().at("/data").size()).isEqualTo(4);
        assertThat(repeat.getBody().at("/data/campaignId").asLong()).isEqualTo(campaignId);
        assertThat(repeat.getBody().at("/data/reservationId").asLong()).isEqualTo(issued.reservationId());
        assertThat(repeat.getBody().at("/data/status").asText()).isEqualTo("ISSUED");
        assertThat(repeat.getBody().at("/data/couponId").asLong()).isEqualTo(issued.couponId());
        assertThat(observer.queryForObject(
            "SELECT COUNT(*) FROM owned_coupon WHERE reservation_id=?", Integer.class, issued.reservationId())).isOne();
        assertThat(observer.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id=?",
            Integer.class, campaignId)).isOne();
    }

    @Test
    void scheduledRecoveryReservesTheConfiguredCampaignWithoutAClientRetry() {
        assertDatabase(SCHEDULED_CAMPAIGN, 0);
        assertThat(selection.select(SCHEDULED_CAMPAIGN, 6)).isEqualTo(CouponSelectionStore.Selection.NEW);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            assertDatabase(SCHEDULED_CAMPAIGN, 1);
            assertThat(selection.pending(SCHEDULED_CAMPAIGN, 1)).isEmpty();
            assertThat(observer.queryForObject("""
                SELECT COUNT(*) FROM coupon_reservation WHERE campaign_id=? AND user_id=6 AND status='WAITING'
                """, Integer.class, SCHEDULED_CAMPAIGN)).isOne();
        });
    }

    @Test
    void identityComesOnlyFromTheExistingHeaderAndInvalidRequestsCannotReserve() {
        long campaignId = 1007;
        prepareOpenCampaign(campaignId, 1);
        String url = "/api/v1/coupon-campaigns/" + campaignId + "/issues";
        assertFailure(http.postForEntity(url, HttpEntity.EMPTY, JsonNode.class), 400, "INVALID_REQUEST");
        assertFailure(apply(campaignId, 999), 404, "USER_NOT_FOUND");

        var headers = new HttpHeaders();
        headers.set("X-USER-ID", "applicant-1");
        headers.setContentType(MediaType.APPLICATION_JSON);
        assertFailure(http.postForEntity(url, new HttpEntity<>("{\"userId\":2}", headers), JsonNode.class),
            400, "INVALID_REQUEST");
        assertFailure(http.postForEntity("/api/v1/coupon-campaigns/abc/issues", new HttpEntity<>(headers), JsonNode.class),
            400, "INVALID_REQUEST");
        assertDatabase(campaignId, 0);
        assertThat(selection.pending(campaignId, 1)).isEmpty();
    }

    @Test
    void inactiveAndUnopenedCampaignsReturnDifferentErrors() {
        long inactive = 1008;
        insertCampaign(observer, inactive, 1);
        observer.update("UPDATE coupon_campaign SET active = FALSE WHERE id = ?", inactive);
        admission.open(inactive);
        assertFailure(apply(inactive, 1), 409, "COUPON_CAMPAIGN_INACTIVE");
        assertDatabase(inactive, 0);

        long unopened = 1009;
        insertCampaign(observer, unopened, 1);
        assertFailure(apply(unopened, 1), 503, "COUPON_ADMISSION_UNAVAILABLE");
        assertDatabase(unopened, 0);
    }

    private void assertFailure(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().at("/meta/result").asText()).isEqualTo("FAIL");
        assertThat(response.getBody().at("/meta/errorCode").asText()).isEqualTo(code);
        assertThat(response.getBody().has("data")).isFalse();
    }

    private void prepareOpenCampaign(long campaignId, int quota) {
        insertCampaign(observer, campaignId, quota);
        admission.open(campaignId);
    }

    private void assertDatabase(long campaignId, int waiting) {
        assertThat(observer.queryForObject("SELECT waiting_count FROM coupon_campaign WHERE id=?", Integer.class, campaignId))
            .isEqualTo(waiting);
        assertThat(observer.queryForObject("SELECT COUNT(*) FROM coupon_reservation WHERE campaign_id=? AND status='WAITING'",
            Integer.class, campaignId)).isEqualTo(waiting);
        assertThat(observer.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id=?", Integer.class, campaignId)).isZero();
        assertThat(observer.queryForObject("""
            SELECT COUNT(*) FROM owned_coupon o JOIN coupon_reservation r ON r.id = o.reservation_id WHERE r.campaign_id=?
            """, Integer.class, campaignId)).isZero();
    }
}
