package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.HoldingAdmissionRepository;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.fixture.CouponFixture;
import com.loopers.fixture.CouponRedisTarget;
import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import com.loopers.infrastructure.coupon.CouponCampaignRepositoryImpl;
import com.loopers.infrastructure.coupon.WorkerTermination;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 HTTP 전송 장애(Q6 응답 유실, Q11 client timeout·연결 종료, Q12 처리 중 시간 경과)를 실제 MySQL·fixture Redis 컨테이너로 확인한다.
 * 클라이언트는 test 의 raw socket 이라 timeout·RST 종료가 실제 TCP 동작이다. 서버 진행 위치는 test decorator 의 유한 barrier 로 통제한다.
 * 제품에는 응답 유실·장애 hook 이 없다.
 *
 * <p>클라이언트 실패·종료는 서버 종료 근거가 아니다. 서버 종료는 수용 decorator 가 관찰한 반환 호출(Facade 의 마지막 단계)과
 * DB 결과로 확인한다. 정리는 {@link ServerSettlement} 판단을 따르고, 근거가 없으면 보류해 같은 컨텍스트의 다음 사례도 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// fixture Redis 주소 주입·decorator 구성으로 이 클래스만의 컨텍스트가 생긴다. 끝나면 닫아 MySQL 연결 풀을 반납한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("HTTP 응답 유실·client timeout·연결 종료·시간 경과가 서버의 발급·TX·token 반환을 앞당기거나 바꾸지 않는다.")
class CouponHttpFaultE2ETest {

    private static final String KEY = CouponAdmissionRedisRepository.PERMITS_KEY;
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);
    private static final Duration SETTLE_DEADLINE = Duration.ofSeconds(10);
    private static final Duration CLIENT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration ELAPSED = Duration.ofSeconds(5);

    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> COUPON =
        new ParameterizedTypeReference<>() {};

    @DynamicPropertySource
    static void pinToFixtureContainer(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
    }

    @TestConfiguration
    static class FaultConfig {
        @Bean
        @Primary
        HoldingAdmissionRepository holdingAdmissionRepository(CouponAdmissionRedisRepository redisRepository) {
            return new HoldingAdmissionRepository(redisRepository);
        }

        @Bean
        ServerSettlement serverSettlement(HoldingAdmissionRepository holdingAdmissionRepository) {
            return new ServerSettlement(holdingAdmissionRepository);
        }

        @Bean
        @Primary
        ReserveHold reserveHold(CouponCampaignRepositoryImpl campaignRepository) {
            return new ReserveHold(campaignRepository);
        }
    }

    /** 실제 조건부 UPDATE 를 실행한 뒤(쓰기 TX 안) 한 번 유한하게 멈춘다. 나머지는 실제 구현에 위임한다. */
    static final class ReserveHold implements CouponCampaignRepository {
        private final CouponCampaignRepository delegate;
        private volatile CountDownLatch reached;
        private volatile Semaphore permits;
        private volatile Duration deadline;

        ReserveHold(CouponCampaignRepository delegate) {
            this.delegate = delegate;
        }

        void armOnce(Duration deadline) {
            this.deadline = deadline;
            this.permits = new Semaphore(0);
            this.reached = new CountDownLatch(1);
        }

        boolean awaitReached(Duration wait) throws InterruptedException {
            return reached.await(wait.toMillis(), TimeUnit.MILLISECONDS);
        }

        boolean isHolding() {
            return reached != null && reached.getCount() == 0 && permits != null && permits.hasQueuedThreads();
        }

        void letGo() {
            Semaphore current = permits;
            if (current != null) {
                current.release(Integer.MAX_VALUE / 2);
            }
        }

        void disarm() {
            letGo();
            reached = null;
            permits = null;
        }

        @Override
        public int reserveOne(Long campaignId, ZonedDateTime now) {
            int affected = delegate.reserveOne(campaignId, now);
            CountDownLatch currentReached = reached;
            Semaphore currentPermits = permits;
            if (currentReached != null && currentReached.getCount() > 0) {
                currentReached.countDown();
                try {
                    if (!currentPermits.tryAcquire(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("reserve barrier deadline 초과");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("reserve barrier interrupt", e);
                }
            }
            return affected;
        }

        @Override
        public CouponCampaignModel save(CouponCampaignModel campaign) {
            return delegate.save(campaign);
        }

        @Override
        public Optional<CouponCampaignIssueData> findIssueData(Long campaignId) {
            return delegate.findIssueData(campaignId);
        }

        @Override
        public Optional<CouponCampaignModel> findById(Long campaignId) {
            return delegate.findById(campaignId);
        }
    }

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CouponFacade couponFacade;
    @Autowired
    private HoldingAdmissionRepository holding;
    @Autowired
    private ServerSettlement settlement;
    @Autowired
    private ReserveHold reserveHold;
    @Autowired
    private CouponFixture fixture;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> master;

    private final WorkerTermination workers = new WorkerTermination();
    private boolean targetVerified;

    @BeforeEach
    void setUp() {
        settlement.requireNotWithheld();
        CouponRedisTarget.verify(redisProperties, master);
        targetVerified = true;
        assertThat(master.type(KEY)).as("시작 전 전용 키가 비어 있어야 한다").isEqualTo(DataType.NONE);
        holding.reset();
        settlement.reset();
        couponFacade.open();
    }

    @AfterEach
    void tearDown() {
        holding.stopHolding();
        reserveHold.disarm();
        couponFacade.close();
        settlement.confirmCleanupOrWithhold(workers.safeToClean(), SETTLE_DEADLINE);
        if (targetVerified && !holding.sentTokens().isEmpty()) {
            master.opsForSet().remove(KEY, holding.sentTokens().toArray());
        }
        databaseCleanUp.truncateAllTables();
        if (targetVerified) {
            assertThat(master.type(KEY)).as("자기 token 정리 뒤 전용 키가 비어야 한다").isEqualTo(DataType.NONE);
        }
    }

    /** 응답을 읽지 않는 raw 요청과 그 미확인 등록 key. 소켓은 호출자가 닫는다. */
    private record RawRequest(Socket socket, String key) {
    }

    /**
     * 응답을 읽지 않는 raw HTTP/1.1 요청. 연결 전에 미확인으로 등록하고, 연결·전송에 실패하면 만든 소켓을 닫고 예외를 던진다.
     * 이 경우에도 등록은 남아 정리를 보류한다(요청이 서버에 닿았는지 모른다).
     */
    private RawRequest sendIssue(Long userId, Long campaignId) throws IOException {
        String key = settlement.registerRaw("raw POST issue user " + userId + " campaign " + campaignId);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/v1/coupon-campaigns/" + campaignId + "/issues HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "X-USER-ID: " + userId + "\r\n"
                + "Content-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return new RawRequest(socket, key);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** RST 로 즉시 끊는다. 이 소켓으로는 응답 바이트를 읽지 않았다. */
    private static void abort(Socket socket) throws IOException {
        socket.setSoLinger(true, 0);
        socket.close();
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(Long userId, Long campaignId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return settlement.observe("POST issue user " + userId, () -> rest.exchange(
            "/api/v1/coupon-campaigns/" + campaignId + "/issues", HttpMethod.POST, new HttpEntity<>(null, headers), COUPON));
    }

    private Long storedCouponId(Long userId, Long campaignId) {
        return jdbcTemplate.queryForObject("SELECT id FROM coupon WHERE user_id = ? AND campaign_id = ?", Long.class, userId, campaignId);
    }

    /**
     * 행을 바꾸고 아직 끝나지 않은 InnoDB TX 수(trx_rows_modified > 0). 조건부 UPDATE 를 실행한 쓰기 TX 가 열려 있는지 본다.
     * 테스트 DB 사용자는 PROCESS 권한이 없어 테스트 컨테이너 root 로 조회한다. 모든 열린 TX 수는 무관한 읽기 TX 를 포함해 쓰지 않는다.
     */
    private static String describeTransactions() throws Exception {
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String password = System.getProperty("datasource.mysql-jpa.main.password");
        StringBuilder out = new StringBuilder();
        try (var connection = DriverManager.getConnection(url, "root", password);
             var statement = connection.prepareStatement("""
                 SELECT t.trx_mysql_thread_id, t.trx_state, t.trx_started, t.trx_rows_modified, t.trx_rows_locked, t.trx_query,
                        p.USER, p.DB, p.COMMAND, p.INFO
                   FROM information_schema.innodb_trx t
                   LEFT JOIN information_schema.processlist p ON p.ID = t.trx_mysql_thread_id
                 """);
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                for (int i = 1; i <= 10; i++) {
                    out.append(rows.getString(i)).append(i < 10 ? "|" : ";");
                }
            }
        }
        return out.toString();
    }

    private static long openWriteTransactions() throws Exception {
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String password = System.getProperty("datasource.mysql-jpa.main.password");
        try (var connection = DriverManager.getConnection(url, "root", password);
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_rows_modified > 0");
             var rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @DisplayName("Q6: commit·DB 결과 확인 뒤 응답 전에 클라이언트 연결을 끊어 201 을 받지 못하게 해도, 서버는 끝까지 처리하고 재요청은 200·같은 couponId·추가 효과 0 이다.")
    @Test
    void responseLostAfterCommit() throws Exception {
        Long user = fixture.user();
        Long campaignId = fixture.activeCampaign(5);
        holding.holdNextReleases(1, DEADLINE);

        RawRequest raw = sendIssue(user, campaignId);
        Socket socket = raw.socket();
        String token;
        boolean held;
        try {
            held = holding.awaitReleaseHeld(DEADLINE);
            assertThat(held).as("쓰기 TX commit 뒤·반환 전 서버가 멈춰야 한다").isTrue();
            assertThat(holding.releaseHeldTokens()).as("이 raw 요청 하나만 반환 직전에 있다").hasSize(1);
            Long committedId = storedCouponId(user, campaignId);
            assertAll(
                () -> assertThat(committedId).as("최초 요청의 발급이 commit 됐다").isNotNull(),
                () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1),
                () -> assertThat(master.opsForSet().members(KEY)).containsExactlyElementsOf(holding.releaseHeldTokens())
            );
        } finally {
            abort(socket); // 응답 바이트를 하나도 읽지 않고 RST 로 끊는다.
            holding.letReleasesThrough(1);
        }
        assertThat(holding.awaitReleases(1, DEADLINE)).as("서버 측 반환 관찰 = Facade 처리 종료").isTrue();
        token = holding.releasedTokens().get(0);
        settlement.settleRaw(raw.key(), token); // 이 요청의 token 반환 관찰로만 해소한다.

        Long committedId = storedCouponId(user, campaignId);
        var retry = issue(user, campaignId);
        System.out.printf("[coupon-q6] campaign=%d user=%d committedCouponId=%d clientReadBytes=0 retry=%s retryCouponId=%d issued=%d coupons=%d releases=%d%n",
            campaignId, user, committedId, retry.getStatusCode(), retry.getBody().data().couponId(),
            fixture.issuedCount(campaignId), fixture.couponCount(campaignId), holding.releasedTokens().size());
        assertAll(
            () -> assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(retry.getBody().data().couponId()).isEqualTo(committedId),
            () -> assertThat(fixture.issuedCount(campaignId)).as("재요청의 추가 발급 효과 없음").isEqualTo(1),
            () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(1),
            () -> assertThat(holding.releasedTokens()).as("재요청은 자리를 쓰지 않는다").hasSize(1),
            () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE)
        );
    }

    @DisplayName("Q11: 쓰기 TX 의 UPDATE 뒤 서버가 머무는 동안 client timeout·연결 종료가 나도 서버 TX 는 살아 있고 token 을 미리 반환하지 않으며, 놓은 뒤 commit·반환까지 끝난다.")
    @Test
    void clientTimeoutAndDisconnectDoNotEndServerWork() throws Exception {
        Long user = fixture.user();
        Long campaignId = fixture.activeCampaign(5);
        reserveHold.armOnce(DEADLINE);

        RawRequest raw = sendIssue(user, campaignId);
        Socket socket = raw.socket();
        long openWhileHeld;
        Set<String> membersWhileHeld;
        try {
            assertThat(reserveHold.awaitReached(DEADLINE)).as("실제 UPDATE 뒤 쓰기 TX 안에서 멈춰야 한다").isTrue();
            socket.setSoTimeout((int) CLIENT_TIMEOUT.toMillis());
            assertThatThrownBy(() -> socket.getInputStream().read()).as("실제 client read timeout")
                .isInstanceOf(SocketTimeoutException.class);
            abort(socket); // 연결 종료(RST)

            Thread.sleep(300);
            openWhileHeld = openWriteTransactions();
            System.out.println("[coupon-q11] trxWhileHeld=" + describeTransactions());
            membersWhileHeld = master.opsForSet().members(KEY);
            assertAll(
                () -> assertThat(reserveHold.isHolding()).as("서버 처리 스레드가 아직 barrier 에 있다").isTrue(),
                () -> assertThat(openWhileHeld).as("UPDATE 를 실행한 쓰기 TX 가 열려 있다").isEqualTo(1),
                () -> assertThat(fixture.issuedCount(campaignId)).as("다른 연결에서는 commit 전 값").isZero(),
                () -> assertThat(membersWhileHeld).as("token 이 남아 있다").hasSize(1),
                () -> assertThat(holding.releasedTokens()).as("조기 반환 없음").isEmpty()
            );
        } finally {
            if (!socket.isClosed()) {
                abort(socket);
            }
            reserveHold.letGo();
        }

        assertThat(holding.awaitReleases(1, DEADLINE)).as("놓은 뒤 서버 처리 종료(반환) 관찰").isTrue();
        assertThat(holding.sentTokens()).as("이 raw 요청 하나만 Redis 를 썼다").hasSize(1);
        settlement.settleRaw(raw.key(), holding.releasedTokens().get(0));
        // innodb_trx 는 최대 0.1초 주기로 갱신되는 캐시라 commit 직후에는 이전 TX 가 보일 수 있다. 유한하게 다시 읽는다.
        long pollStart = System.nanoTime();
        long polled = openWriteTransactions();
        while (polled != 0 && System.nanoTime() - pollStart < Duration.ofSeconds(10).toNanos()) {
            Thread.sleep(100);
            polled = openWriteTransactions();
        }
        long openAfter = polled;
        long polledMs = Duration.ofNanos(System.nanoTime() - pollStart).toMillis();
        Long couponId = storedCouponId(user, campaignId);
        System.out.printf("[coupon-q11] campaign=%d user=%d clientTimeoutMs=%d openWriteTrxWhileHeld=%d membersWhileHeld=%d openWriteTrxAfter=%d (polledMs=%d) issued=%d couponId=%d releases=%d keyType=%s%n",
            campaignId, user, CLIENT_TIMEOUT.toMillis(), openWhileHeld, membersWhileHeld.size(), openAfter, polledMs,
            fixture.issuedCount(campaignId), couponId, holding.releasedTokens().size(), master.type(KEY));
        assertAll(
            () -> assertThat(couponId).as("연결이 끊긴 요청도 서버에서 commit").isNotNull(),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1),
            () -> assertThat(openAfter).isZero(),
            () -> assertThat(holding.releasedTokens()).hasSize(1),
            () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE),
            () -> assertThat(couponFacade.status()).isTrue()
        );
    }

    @DisplayName("Q12: C=3 자리를 실제 HTTP 처리 세 건이 쥔 채 5초가 지나도 TTL 은 없고 token 은 그대로이며 새 요청은 429 다. 놓으면 셋 다 201 로 끝나고 키가 빈다.")
    @Test
    void elapsedTimeDoesNotFreeActivePermits() throws Exception {
        Long campaignId = fixture.activeCampaign(10);
        List<Long> users = fixture.users(4);
        holding.holdNextAcquired(3, DEADLINE.plus(ELAPSED));
        ExecutorService executor = workers.start(3);
        List<Future<ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>>>> futures = new ArrayList<>();
        List<String> heldTokens;
        long ttlBefore;
        long ttlAfter;
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> fourth;
        long waitedMs;
        try {
            for (int i = 0; i < 3; i++) {
                Long user = users.get(i);
                futures.add(executor.submit(() -> issue(user, campaignId)));
            }
            assertThat(holding.awaitHeld(DEADLINE)).isTrue();
            heldTokens = holding.heldTokens();
            ttlBefore = master.getExpire(KEY);
            long start = System.nanoTime();
            Thread.sleep(ELAPSED.toMillis());
            waitedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            ttlAfter = master.getExpire(KEY);
            Set<String> membersAfter = master.opsForSet().members(KEY);
            fourth = issue(users.get(3), campaignId);
            assertAll(
                () -> assertThat(ttlBefore).isEqualTo(-1L),
                () -> assertThat(ttlAfter).isEqualTo(-1L),
                () -> assertThat(membersAfter).containsExactlyInAnyOrderElementsOf(heldTokens),
                () -> assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS),
                () -> assertThat(fixture.issuedCount(campaignId)).isZero()
            );
            holding.letThrough(3);
        } finally {
            holding.stopHolding();
            workers.close(executor, TERMINATION_DEADLINE);
        }
        List<HttpStatus> statuses = new ArrayList<>();
        for (var future : futures) {
            statuses.add(HttpStatus.valueOf(future.get(0, TimeUnit.MILLISECONDS).getStatusCode().value()));
        }
        assertThat(holding.awaitReleases(3, DEADLINE)).isTrue();
        System.out.printf("[coupon-q12] campaign=%d C=3 held=%d waitedMs=%d ttlBefore=%d ttlAfter=%d fourth=%s heldStatuses=%s issued=%d keyType=%s%n",
            campaignId, heldTokens.size(), waitedMs, ttlBefore, ttlAfter, fourth.getStatusCode(), statuses,
            fixture.issuedCount(campaignId), master.type(KEY));
        assertAll(
            () -> assertThat(statuses).containsOnly(HttpStatus.CREATED),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(3),
            () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE)
        );
    }
}
