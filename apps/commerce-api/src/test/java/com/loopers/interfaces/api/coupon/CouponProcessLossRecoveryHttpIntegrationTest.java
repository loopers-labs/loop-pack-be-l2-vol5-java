package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
@Timeout(150)
class CouponProcessLossRecoveryHttpIntegrationTest {
    private static final long CAMPAIGN = 8401L;
    private static final String ISSUES = "/api/v1/coupon-campaigns/" + CAMPAIGN + "/issues";
    private static final String SELECTION = "commerce:coupon-selection:v1:{" + CAMPAIGN + "}:state";
    private static final String WORK = "commerce:coupon-selection:v1:{" + CAMPAIGN + "}:work";
    private static final String TOKENS = "commerce:coupon-issue:v1:{global}:tokens";
    private static final String STATE = "commerce:coupon-issue:v1:{global}:state";
    @Container
    private final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("w8_coupon_process_loss_test");
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void freshWorkerRecoversTheFourthCustomersCommittedReservationAfterAppAndRedisLoss(TestReporter report) throws Exception {
        var db = prepareDatabase();
        long reservation;
        long oldPid;
        String oldRedisId;
        String oldEpoch;
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            try (var oldRedis = emptyRedis()) {
                oldRedis.start();
                oldRedisId = oldRedis.getContainerId();
                try (var original = OwnedCouponApplication.start(mysql, oldRedis, CAMPAIGN, true, directory.resolve("original"))) {
                    oldPid = original.pid();
                    var receipt = send(http, customerRequest(original, ISSUES).POST(HttpRequest.BodyPublishers.noBody()).build(), 202);
                    assertThat(receipt.get("status").textValue()).isEqualTo("WAITING");
                    reservation = receipt.get("reservationId").longValue();
                    assertThat(receipt.has("couponId")).isFalse();
                    assertWaiting(db, reservation);
                    await().atMost(Duration.ofSeconds(10)).until(original::issuancePaused);
                    assertThat(redis(oldRedis, "SCARD", TOKENS)).as("old worker took its only slot before T2").isEqualTo("1");
                    assertThat(redis(oldRedis, "HGET", SELECTION, "4")).isEqualTo("committed");
                    oldEpoch = redis(oldRedis, "HGET", SELECTION, "@epoch");
                    assertThat(oldEpoch).isNotBlank();
                    original.kill();
                }
                assertThat(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            }

            // Destroy the old Redis container before provisioning a fresh one. There is no shared mount or copied snapshot.
            try (var freshRedis = emptyRedis()) {
                freshRedis.start();
                assertThat(freshRedis.getContainerId()).isNotEqualTo(oldRedisId);
                assertThat(redis(freshRedis, "DBSIZE")).isEqualTo("0");
                assertWaiting(db, reservation);
                long recoveredPid;
                long coupon;
                try (var recovery = OwnedCouponApplication.start(mysql, freshRedis, CAMPAIGN, false, directory.resolve("recovery"))) {
                    recoveredPid = recovery.pid();
                    assertThat(recoveredPid).isNotEqualTo(oldPid);
                    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(state(db, reservation)).as("customer 4 original reservation after restart")
                            .isEqualTo(new ReservationState("ISSUED", 0, 1, 1)));
                    assertThat(recovery.sawWaiting(reservation)).as("fresh worker read the original MySQL WAITING reservation").isTrue();
                    coupon = ownedCouponId(db, reservation);
                    assertCustomerReads(http, recovery, reservation, coupon);
                    String rebuiltEpoch = redis(freshRedis, "HGET", SELECTION, "@epoch");
                    assertThat(rebuiltEpoch).isNotBlank().isNotEqualTo(oldEpoch);
                    assertThat(redis(freshRedis, "HGET", SELECTION, "4")).isEqualTo("committed");
                    assertThat(redis(freshRedis, "XLEN", WORK)).isEqualTo("0");
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(redis(freshRedis, "SCARD", TOKENS)).isEqualTo("0"));
                }
                try (var repeat = OwnedCouponApplication.start(mysql, freshRedis, CAMPAIGN, false, directory.resolve("repeat"))) {
                    assertThat(repeat.pid()).isNotIn(oldPid, recoveredPid);
                    await().atMost(Duration.ofSeconds(10)).until(repeat::hasPolled);
                    assertCustomerReads(http, repeat, reservation, coupon);
                    assertThat(state(db, reservation)).isEqualTo(new ReservationState("ISSUED", 0, 1, 1));
                    assertThat(ownedCouponId(db, reservation)).isEqualTo(coupon);
                    assertThat(db.queryForObject("SELECT COUNT(*) FROM coupon_reservation", Long.class)).isEqualTo(1L);
                    assertThat(redis(freshRedis, "SCARD", TOKENS)).isEqualTo("0");
                    report.publishEntry(Map.of("appPids", oldPid + "/" + recoveredPid + "/" + repeat.pid(),
                        "userId", "4", "reservationId", Long.toString(reservation), "couponId", Long.toString(coupon),
                        "finalWaiting", "0", "finalIssued", "1", "finalOwned", "1"));
                }
            }
        }
    }

    @Test
    void operatorRecoversOneEndedWorkersTokenOnRetainedRedisAndResumesTheOriginalReservation() throws Exception {
        var db = prepareDatabase();
        Path originalDir = directory.resolve("original");
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
             var retained = emptyRedis()) {
            retained.start();
            long reservation;
            long oldPid;
            String token;
            String owner;
            String epoch;
            try (var original = OwnedCouponApplication.startHoldingIssuanceTransaction(mysql, retained, CAMPAIGN, originalDir)) {
                oldPid = original.pid();
                var receipt = send(http, customerRequest(original, ISSUES).POST(HttpRequest.BodyPublishers.noBody()).build(), 202);
                reservation = receipt.get("reservationId").longValue();
                assertThat(receipt.get("status").textValue()).isEqualTo("WAITING");
                await().atMost(Duration.ofSeconds(10)).until(original::issuancePaused);
                assertThat(redis(retained, "SCARD", TOKENS)).isEqualTo("1");
                token = redis(retained, "SMEMBERS", TOKENS);
                owner = redis(retained, "HGET", STATE, "owner:" + token);
                epoch = redis(retained, "HGET", STATE, "epoch");
                assertThat(owner).isNotBlank();
                assertThat(epoch).isNotBlank();
                assertThat(Files.readString(originalDir.resolve("child.log")))
                    .contains("Opened coupon issuance semaphore owner=" + owner + " epoch=" + epoch)
                    .contains("Acquired coupon issuance token=" + token + " campaign=" + CAMPAIGN + " reservation=" + reservation + " user=4");
                // From a separate connection the real INSERT is invisible and the issuing TX owns this campaign row.
                assertWaiting(db, reservation);
                assertThatThrownBy(() -> settled(db, reservation, 4)).rootCause()
                    .isInstanceOfSatisfying(SQLException.class, sql -> assertThat(sql.getErrorCode()).isEqualTo(3572));
                original.kill();
            }
            assertThat(ProcessHandle.of(oldPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            await().atMost(Duration.ofSeconds(8)).ignoreExceptions().untilAsserted(() ->
                assertThat(settled(db, reservation, 4)).isEqualTo(new Settlement("WAITING", 0, null)));
            assertThatThrownBy(() -> settled(db, reservation, 5)).isInstanceOf(EmptyResultDataAccessException.class);

            Path closedDir = directory.resolve("closed");
            try (var closed = OwnedCouponApplication.start(mysql, retained, CAMPAIGN, false, closedDir)) {
                assertThat(Files.readString(closedDir.resolve("child.log")))
                    .contains("Coupon issuance remains closed after startup reconciliation failed");
                var status = send(http, customerRequest(closed, ISSUES + "/me").GET().build(), 200);
                assertThat(status.get("status").textValue()).isEqualTo("WAITING");
                assertThat(status.get("reservationId").longValue()).isEqualTo(reservation);
                assertThat(redis(retained, "SMEMBERS", TOKENS)).isEqualTo(token);
                assertThat(redis(retained, "HGET", STATE, "owner:" + token)).isEqualTo(owner);

                String lua = new ClassPathResource("redis/coupon-issue-semaphore.lua").getContentAsString(StandardCharsets.UTF_8);
                assertThat(redis(retained, "EVAL", lua, "2", STATE, TOKENS, "recover-one", epoch, "1", owner, token))
                    .as("explicitly retire only the previously confirmed inactive token").isEqualTo("1");
                assertThat(redis(retained, "SCARD", TOKENS)).isEqualTo("0");
                assertThat(redis(retained, "HGET", STATE, "held")).isEqualTo("0");
                assertThat(redis(retained, "HEXISTS", STATE, "owner:" + token)).isEqualTo("0");
                assertThat(redis(retained, "HGET", STATE, "epoch")).isEqualTo(epoch);
                // Removing a verified orphan does not reopen the current worker; its ordinary poll interval is 100 ms.
                await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    assertWaiting(db, reservation);
                    assertThat(closed.hasPolled()).isFalse();
                });
            }

            long coupon;
            try (var resumed = OwnedCouponApplication.start(mysql, retained, CAMPAIGN, false, directory.resolve("resumed"))) {
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(state(db, reservation)).isEqualTo(new ReservationState("ISSUED", 0, 1, 1)));
                assertThat(resumed.sawWaiting(reservation)).isTrue();
                coupon = ownedCouponId(db, reservation);
                assertCustomerReads(http, resumed, reservation, coupon);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(redis(retained, "SCARD", TOKENS)).isEqualTo("0"));
            }
            try (var repeat = OwnedCouponApplication.start(mysql, retained, CAMPAIGN, false, directory.resolve("repeat"))) {
                await().atMost(Duration.ofSeconds(5)).until(repeat::hasPolled);
                assertCustomerReads(http, repeat, reservation, coupon);
                assertThat(settled(db, reservation, 4)).isEqualTo(new Settlement("ISSUED", 1, coupon));
                assertThat(state(db, reservation)).isEqualTo(new ReservationState("ISSUED", 0, 1, 1));
                assertThat(redis(retained, "SCARD", TOKENS)).isEqualTo("0");
            }
        }
    }

    private Settlement settled(JdbcTemplate db, long reservation, long userId) throws IOException {
        String verification = new ClassPathResource("db/operations/verify-coupon-issuance-settlement.sql")
            .getContentAsString(StandardCharsets.UTF_8);
        var manager = new DataSourceTransactionManager(db.getDataSource());
        return new TransactionTemplate(manager).execute(tx -> {
            db.queryForObject("SELECT id FROM coupon_campaign WHERE id = ? FOR UPDATE NOWAIT", Long.class, CAMPAIGN);
            db.update("SET @campaign_id = ?, @reservation_id = ?, @user_id = ?", CAMPAIGN, reservation, userId);
            return db.queryForObject(verification, (row, i) -> new Settlement(row.getString("status"),
                row.getInt("owned_count"), row.getObject("coupon_id", Long.class)));
        });
    }

    private JdbcTemplate prepareDatabase() {
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        new ResourceDatabasePopulator(new ClassPathResource("db/schema/002-user.sql"),
            new ClassPathResource("db/schema/013-coupon-issuance.sql"),
            new ClassPathResource("db/schema/014-coupon-waiting-index.sql")).execute(source);
        var db = new JdbcTemplate(source);
        db.update("INSERT INTO `user` (id, point_balance, created_at, updated_at) VALUES (4, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        CouponHttpIntegrationFixture.insertCampaign(db, CAMPAIGN, 1);
        return db;
    }

    private GenericContainer<?> emptyRedis() {
        return new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379)
            .withCommand("redis-server", "--save", "", "--appendonly", "no");
    }

    private String redis(GenericContainer<?> container, String... command) throws Exception {
        String[] args = new String[command.length + 2];
        args[0] = "redis-cli";
        args[1] = "--raw";
        System.arraycopy(command, 0, args, 2, command.length);
        var response = container.execInContainer(args);
        assertThat(response.getExitCode()).isZero();
        return response.getStdout().strip();
    }

    private HttpRequest.Builder customerRequest(OwnedCouponApplication app, String path) {
        return HttpRequest.newBuilder(app.uri(path)).timeout(Duration.ofSeconds(8)).header("X-USER-ID", "applicant-4");
    }

    private JsonNode send(HttpClient http, HttpRequest request, int expectedStatus) throws Exception {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", request.method(), request.uri(), response.body()).isEqualTo(expectedStatus);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.at("/meta/result").textValue()).isEqualTo("SUCCESS");
        return body.get("data");
    }

    private void assertCustomerReads(HttpClient http, OwnedCouponApplication app, long id, long coupon) throws Exception {
        var saved = send(http, customerRequest(app, ISSUES + "/me").GET().build(), 200);
        assertThat(saved.get("status").textValue()).isEqualTo("ISSUED");
        assertThat(saved.get("reservationId").longValue()).isEqualTo(id);
        assertThat(saved.get("couponId").longValue()).isEqualTo(coupon);
        var list = send(http, customerRequest(app, "/api/v1/coupons").GET().build(), 200);
        assertThat(list.get("totalElements").longValue()).isEqualTo(1L);
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.at("/items/0/couponId").longValue()).isEqualTo(coupon);
    }

    private void assertWaiting(JdbcTemplate db, long id) {
        assertThat(state(db, id)).isEqualTo(new ReservationState("WAITING", 1, 0, 0));
    }

    private ReservationState state(JdbcTemplate db, long id) {
        return db.queryForObject("""
            SELECT r.status, c.waiting_count, c.issued_count,
                   (SELECT COUNT(*) FROM owned_coupon o WHERE o.reservation_id = r.id) AS owned
              FROM coupon_reservation r JOIN coupon_campaign c ON c.id = r.campaign_id
             WHERE r.id = ? AND r.campaign_id = ? AND r.user_id = 4
            """, (row, n) -> new ReservationState(row.getString("status"), row.getInt("waiting_count"),
                row.getInt("issued_count"), row.getInt("owned")), id, CAMPAIGN);
    }

    private long ownedCouponId(JdbcTemplate db, long reservation) {
        return db.queryForObject("SELECT id FROM owned_coupon WHERE reservation_id=?", Long.class, reservation);
    }

    private record ReservationState(String status, int waiting, int issued, int owned) { }

    private record Settlement(String status, int ownedCount, Long couponId) { }
}
