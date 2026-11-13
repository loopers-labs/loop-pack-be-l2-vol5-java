package com.loopers.interfaces.api.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.coupon.command.CouponCampaignCommand;
import com.loopers.application.coupon.command.CouponIssueCommand;
import com.loopers.application.coupon.service.IssueCouponService;
import com.loopers.application.coupon.service.PrepareCouponCampaignService;
import com.loopers.application.coupon.support.CouponAdmissionControl;
import com.loopers.application.coupon.support.CouponExecution;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.infrastructure.cache.coupon.RedisCouponIssueCounter;
import com.loopers.support.test.E2ETest;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@E2ETest
@Testcontainers
@TestPropertySource(properties = {"datasource.mysql-jpa.main.username=root", "datasource.mysql-jpa.main.password=test"})
class CouponApiE2ETest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.0").withExposedPorts(6379);
    private static final String ADMIN = "/api/v1/admin/coupon-admission";

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired private TestRestTemplate http;
    @Autowired private UserRepository users;
    @Autowired private PrepareCouponCampaignService preparation;
    @Autowired private CouponAdmissionControl control;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("couponCounterRedisTemplate") private RedisTemplate<String, String> redis;
    @MockitoSpyBean private IssueCouponService issuance;
    private final Set<Long> campaigns = new HashSet<>();

    @BeforeEach
    void setUp() {
        control.close("test-start");
        for (long id = 1; id <= 8; id++) { users.save(User.create(id)); }
    }

    @AfterEach
    void tearDown() {
        control.close("test-end");
        for (long campaign : campaigns) {
            Set<String> keys = redis.keys("coupon:counter:{" + campaign + "}:*");
            if (keys != null && !keys.isEmpty()) { redis.delete(keys); }
        }
        cleanUp.truncateAllTables();
    }

    @Test
    void issuesCreatedThenSameExistingResultAndReturnsOnlyOwnedCoupons() {
        long campaign = campaign(2);
        open();
        assertThat(request(HttpMethod.POST, counter(campaign) + "/rebuild", "1", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> created = request(HttpMethod.POST, issue(campaign) + "?userId=3", "2", Map.of("userId", 3));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode first = body(created);
        ResponseEntity<JsonNode> duplicate = request(HttpMethod.POST, issue(campaign), "2", null);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(duplicate).path("data")).isEqualTo(first.path("data"));
        Set<String> names = new HashSet<>();
        first.path("data").fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("couponId", "campaignId", "issuedAt", "benefitDescription", "discountAmount");
        assertThat(first.path("data").path("issuedAt").asText()).endsWith("Z");
        assertThat(body(request(HttpMethod.GET, "/api/v1/coupons", "2", null)).path("data")).hasSize(1);
        assertThat(body(request(HttpMethod.GET, "/api/v1/coupons", "3", null)).path("data")).isEmpty();
        JsonNode count = body(request(HttpMethod.GET, counter(campaign), "1", null)).path("data");
        assertThat(count.path("prepared").asBoolean()).isTrue();
        assertThat(count.path("aggregateCount").asLong()).isEqualTo(1);
    }

    @Test
    void requiresExistingUserAndPositivePathAndIgnoresForeignUserInput() {
        long campaign = campaign(1);
        assertError(request(HttpMethod.GET, "/api/v1/coupons", null, null), HttpStatus.BAD_REQUEST, "Bad Request");
        assertError(request(HttpMethod.GET, "/api/v1/coupons", "bad", null), HttpStatus.BAD_REQUEST, "Bad Request");
        assertError(request(HttpMethod.GET, "/api/v1/coupons", "99999", null), HttpStatus.NOT_FOUND, "Not Found");
        assertError(request(HttpMethod.POST, issue(-1), "2", null), HttpStatus.BAD_REQUEST, "Bad Request");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons WHERE campaign_id = ?", Long.class, campaign)).isZero();
    }

    @Test
    void mapsBusinessRejectionsAndKeepsCompletedResultsWhenAdmissionCloses() {
        long soldOut = campaign(1);
        long inactive = campaign(1);
        preparation.execute(new CouponCampaignCommand.Activation(inactive, false));
        open();
        assertError(request(HttpMethod.POST, issue(Long.MAX_VALUE), "2", null), HttpStatus.NOT_FOUND, "COUPON_CAMPAIGN_NOT_FOUND");
        assertError(request(HttpMethod.POST, issue(inactive), "2", null), HttpStatus.CONFLICT, "COUPON_CAMPAIGN_INACTIVE");
        ResponseEntity<JsonNode> issued = request(HttpMethod.POST, issue(soldOut), "2", null);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertError(request(HttpMethod.POST, issue(soldOut), "3", null), HttpStatus.CONFLICT, "COUPON_SOLD_OUT");
        assertThat(request(HttpMethod.POST, ADMIN + "/close", "1", Map.of("reason", "manual test")).getStatusCode())
            .isEqualTo(HttpStatus.OK);
        assertError(request(HttpMethod.POST, issue(inactive), "4", null), HttpStatus.SERVICE_UNAVAILABLE, "COUPON_ADMISSION_UNAVAILABLE");
        assertThat(request(HttpMethod.POST, issue(soldOut), "2", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(request(HttpMethod.GET, "/api/v1/coupons", "2", null)).path("data")).hasSize(1);
    }

    @Test
    void administrativeRequestsRequireFixtureAdminAndExplicitVerifiedBoot() {
        String boot = control.status().bootId();
        assertError(request(HttpMethod.GET, ADMIN, "2", null), HttpStatus.FORBIDDEN, "COUPON_ADMIN_FORBIDDEN");
        assertError(request(HttpMethod.POST, ADMIN + "/close", "2", Map.of("reason", "forbidden")),
            HttpStatus.FORBIDDEN, "COUPON_ADMIN_FORBIDDEN");
        assertError(request(HttpMethod.POST, ADMIN + "/open", "1", Map.of("expectedBootId", "wrong")),
            HttpStatus.CONFLICT, "COUPON_RECOVERY_REJECTED");
        assertError(request(HttpMethod.POST, ADMIN + "/recover", "1", Map.of("expectedBootId", boot, "executionIds", List.of())),
            HttpStatus.BAD_REQUEST, "Bad Request");
        assertError(request(HttpMethod.POST, ADMIN + "/verify-restart", "1", Map.of("expectedBootId", boot,
            "previousProcessStopped", true, "auditReference", "missing-history", "initialDeployment", false,
            "previousSessions", List.of())), HttpStatus.CONFLICT, "COUPON_RECOVERY_REJECTED");
        assertError(request(HttpMethod.POST, ADMIN + "/open", "1", Map.of("expectedBootId", boot)),
            HttpStatus.CONFLICT, "COUPON_RECOVERY_REJECTED");
        assertError(request(HttpMethod.POST, ADMIN + "/verify-restart", "1", Map.of("expectedBootId", boot,
            "previousProcessStopped", true, "auditReference", "bad-session", "initialDeployment", false,
            "previousSessions", List.of(Map.of("serverId", "server", "connectionId", -1)))), HttpStatus.BAD_REQUEST, "Bad Request");
        assertThat(control.status().open()).isFalse();
        open();
        assertError(request(HttpMethod.POST, ADMIN + "/recover", "1", Map.of("expectedBootId", boot,
            "executionIds", List.of(boot + ":missing"))), HttpStatus.CONFLICT, "COUPON_RECOVERY_REJECTED");
        request(HttpMethod.POST, ADMIN + "/close", "1", Map.of("reason", "recover test"));
        JsonNode results = body(request(HttpMethod.POST, ADMIN + "/recover", "1", Map.of("expectedBootId", boot,
            "executionIds", List.of(boot + ":missing", "older-boot:execution")))).path("data");
        assertThat(results.get(0).path("result").asText()).isEqualTo("NOT_FOUND");
        assertThat(results.get(1).path("result").asText()).isEqualTo("OTHER_BOOT");
        assertThat(control.status().open()).isFalse();
    }

    @Test
    void redisPostCommitErrorPreservesHttpSuccessAndCounterAdministrationDistinguishesMissingFromZero() {
        long campaign = campaign(2);
        open();
        JsonNode missing = body(request(HttpMethod.GET, counter(campaign), "1", null)).path("data");
        assertThat(missing.path("prepared").asBoolean()).isFalse();
        assertThat(missing.hasNonNull("aggregateCount")).isFalse();
        request(HttpMethod.POST, counter(campaign) + "/rebuild", "1", null);
        assertThat(body(request(HttpMethod.GET, counter(campaign), "1", null)).path("data").path("aggregateCount").asLong())
            .isZero();
        redis.opsForValue().set(RedisCouponIssueCounter.activeKey(campaign), "wrong-type");
        ResponseEntity<JsonNode> issued = request(HttpMethod.POST, issue(campaign), "2", null);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(control.status().available()).isEqualTo(3);
        assertThat(control.status().open()).isTrue();
        assertError(request(HttpMethod.GET, counter(campaign), "1", null), HttpStatus.SERVICE_UNAVAILABLE,
            "COUPON_COUNTER_UNAVAILABLE");
        assertThat(request(HttpMethod.POST, counter(campaign) + "/rebuild", "1", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(request(HttpMethod.GET, counter(campaign), "1", null)).path("data").path("databaseCount").asLong())
            .isEqualTo(1);
        assertThat(request(HttpMethod.POST, counter(campaign) + "/rebuild", "2", null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void realHttpRequestsShareThreePermitsAndFourthReceives429BeforeDatabaseIssuance() throws Exception {
        long campaign = campaign(4);
        open();
        CountDownLatch entered = new CountDownLatch(3);
        Semaphore release = new Semaphore(0);
        doAnswer(invocation -> {
            CouponIssueCommand command = invocation.getArgument(0);
            if (command.userId() >= 2 && command.userId() <= 4) {
                entered.countDown();
                if (!release.tryAcquire(15, TimeUnit.SECONDS)) { throw new IllegalStateException("HTTP barrier timeout"); }
            }
            return invocation.callRealMethod();
        }).when(issuance).execute(any(CouponIssueCommand.class), any(CouponExecution.class));
        var executor = Executors.newFixedThreadPool(3);
        try {
            Future<ResponseEntity<JsonNode>> first = executor.submit(() -> request(HttpMethod.POST, issue(campaign), "2", null));
            Future<ResponseEntity<JsonNode>> second = executor.submit(() -> request(HttpMethod.POST, issue(campaign), "3", null));
            Future<ResponseEntity<JsonNode>> third = executor.submit(() -> request(HttpMethod.POST, issue(campaign), "4", null));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(body(request(HttpMethod.GET, ADMIN, "1", null)).path("data").path("running").asLong()).isEqualTo(3);
            assertError(request(HttpMethod.POST, issue(campaign), "5", null), HttpStatus.TOO_MANY_REQUESTS, "COUPON_CAPACITY_EXCEEDED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupons", Long.class)).isZero();
            release.release(3);
            assertThat(first.get(15, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.get(15, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(third.get(15, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(request(HttpMethod.POST, issue(campaign), "5", null).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(control.status().available()).isEqualTo(3);
        } finally {
            release.release(3);
            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void open() {
        String boot = control.status().bootId();
        assertThat(request(HttpMethod.POST, ADMIN + "/verify-restart", "1", Map.of("expectedBootId", boot,
            "previousProcessStopped", true, "auditReference", "test-no-prior-process", "initialDeployment", true,
            "previousSessions", List.of())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(request(HttpMethod.POST, ADMIN + "/open", "1", Map.of("expectedBootId", boot)).getStatusCode())
            .isEqualTo(HttpStatus.OK);
    }

    private long campaign(int quota) {
        Instant now = Instant.now();
        long id = preparation.execute(new CouponCampaignCommand.Create("HTTP test", quota, "1000원 할인", 1000L,
            now.minusSeconds(3600), now.plusSeconds(3600))).campaignId();
        preparation.execute(new CouponCampaignCommand.Activation(id, true));
        campaigns.add(id);
        return id;
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, String user, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (user != null) { headers.set("X-USER-ID", user); }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static JsonNode body(ResponseEntity<JsonNode> response) {
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static void assertError(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(body(response).path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(body(response).hasNonNull("data")).isFalse();
    }

    private static String issue(long campaign) { return "/api/v1/coupon-campaigns/" + campaign + "/issues"; }
    private static String counter(long campaign) { return "/api/v1/admin/coupon-campaigns/" + campaign + "/counter"; }
}
