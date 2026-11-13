package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Tag("coupon-load-measurement")
class CouponLoadMeasurementTest extends CouponLoadFixture {
    private static final int APPLICANTS = 100_000;
    private static final int CONCURRENCY = 64;
    private static final int QUOTA = 100;
    @Autowired @Qualifier("redisTemplateMaster") RedisTemplate<String, String> master;

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void oneHundredThousandApplicantsWithConcurrentProductAndOrderReads() throws Exception {
        var db = observer();
        assertThat(CouponLoadDatabase.state(db, CAMPAIGN))
            .isEqualTo(new CouponLoadDatabase.State(QUOTA, 0, 0, 0, 0, 0, 0, 0));
        Map<String, CouponLoadProbe.Window> windows = new LinkedHashMap<>();
        var result = CouponLoadDriver.run(URI.create("http://127.0.0.1:" + port), CAMPAIGN, PRODUCT, ORDER,
            "applicant-1", id -> "applicant-" + id, APPLICANTS, CONCURRENCY,
            phase -> {
                if ("baseline".equals(phase)) {
                    // Exclude deferred issuance from the coupon-free baseline as well as new submissions.
                    await().atMost(Duration.ofSeconds(90)).until(() -> CouponLoadDatabase.state(db, CAMPAIGN).waitingCount() == 0
                        && Long.valueOf(0L).equals(master.opsForSet().size("commerce:coupon-issue:v1:{global}:tokens")));
                }
                probe.startWindow();
            }, phase -> windows.put(phase, probe.finishWindow()));

        var state = CouponLoadDatabase.state(db, CAMPAIGN);
        var winners = CouponLoadDatabase.winners(db, CAMPAIGN);
        Map<Long, Long> receipts = new TreeMap<>();
        result.coupons().acceptedReservations().forEach((applicant, reservation) -> receipts.put(applicant.longValue(), reservation));
        String selection = "commerce:coupon-selection:v1:{" + CAMPAIGN + "}:state";
        Map<String, String> selected = master.<String, String>opsForHash().entries(selection);
        Map<Long, String> selectedUsers = new HashMap<>();
        selected.forEach((field, value) -> {
            if (!field.startsWith("@")) {
                selectedUsers.put(Long.parseLong(field), value);
            }
        });
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("applicants", APPLICANTS);
        evidence.put("concurrentCouponClients", CONCURRENCY);
        evidence.put("http", result);
        evidence.put("jdbc", windows);
        evidence.put("mysql", state);
        evidence.put("matchedDatabaseReceipts", winners.equals(receipts));
        evidence.put("redisSelectedUsers", selectedUsers.size());
        evidence.put("redisCommittedUsers", selectedUsers.values().stream().filter("committed"::equals).count());
        evidence.put("redisPending", selected.get("@pending"));
        Path report = Path.of(System.getProperty("w8.coupon.load.report"));
        Files.createDirectories(report.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(report.toFile(), evidence);

        assertThat(result.coupons().requests()).isEqualTo(APPLICANTS);
        assertThat(result.coupons().outcomes().get("409 COUPON_CAMPAIGN_SOLD_OUT")).isEqualTo((long) APPLICANTS - QUOTA);
        assertThat(receipts).hasSize(QUOTA).isEqualTo(winners);
        assertThat(state).isEqualTo(new CouponLoadDatabase.State(QUOTA, 0, QUOTA, QUOTA, QUOTA, QUOTA, QUOTA, QUOTA));
        assertThat(selectedUsers.keySet()).isEqualTo(winners.keySet());
        assertThat(selectedUsers.values()).containsOnly("committed");
        assertThat(selected.get("@pending")).isEqualTo("0");

        var statuses = windows.get("loaded").couponByStatus();
        var soldOut = statuses.get(409);
        assertThat(soldOut.requests()).isEqualTo((long) APPLICANTS - QUOTA);
        assertThat(soldOut.requestsUsingDb()).isZero();
        assertThat(soldOut.connections()).isZero();
        assertThat(soldOut.statements()).isZero();
        // Positive controls: the same instrumentation must see DB work on successful application requests.
        long dbWinners = statuses.entrySet().stream().filter(entry -> entry.getKey() / 100 == 2)
            .mapToLong(entry -> entry.getValue().requestsUsingDb()).sum();
        long winnerSql = statuses.entrySet().stream().filter(entry -> entry.getKey() / 100 == 2)
            .mapToLong(entry -> entry.getValue().statements()).sum();
        assertThat(dbWinners).isEqualTo(QUOTA);
        assertThat(winnerSql).isPositive();
    }
}
