package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.HoldingWaitingRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponWaitingState;
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
 * 실제 HTTP 전송 장애(Q6 응답 유실, Q11 client timeout·연결 종료, Q12 처리 중 시간 경과)와 입장 기한 만료를 대기실 경로로
 * 실제 MySQL·fixture Redis 컨테이너에서 확인한다(설계 §12.6·12.13 WQ4·WQ6).
 * 클라이언트는 test 의 raw socket 이라 timeout·RST 종료가 실제 TCP 동작이다. 서버 진행 위치는 test decorator 의 유한 barrier 로 통제한다.
 * 제품에는 응답 유실·장애 hook 이 없다. 운영 캠페인 ID 는 시작 속성으로 고정하고, 입장 기한은 관찰을 위해 이 컨텍스트만 2초로 줄인다.
 *
 * <p>클라이언트 실패·종료는 서버 종료 근거가 아니다. 서버 종료는 대기실 decorator 가 관찰한 finish(Facade 발급의 마지막 단계)와
 * DB 결과로 확인한다. 정리는 {@link ServerSettlement} 판단을 따르고, 근거가 없으면 보류해 같은 컨텍스트의 다음 사례도 막는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// fixture Redis 주소 주입·decorator 구성으로 이 클래스만의 컨텍스트가 생긴다. 끝나면 닫아 MySQL 연결 풀을 반납한다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("HTTP 응답 유실·client timeout·연결 종료·시간 경과가 서버의 발급·TX·자리 반환을 앞당기거나 바꾸지 않는다.")
class CouponHttpFaultE2ETest {

    private static final long OPERATING = 9_002L;
    private static final Duration ADMISSION_DURATION = Duration.ofSeconds(2);
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);
    private static final Duration SETTLE_DEADLINE = Duration.ofSeconds(10);
    private static final Duration CLIENT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration ELAPSED = Duration.ofSeconds(5);

    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> COUPON =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.WaitingResponse>> WAITING =
        new ParameterizedTypeReference<>() {};

    @DynamicPropertySource
    static void pinToFixtureContainer(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
        registry.add("commerce.coupon.waiting.campaign-id", () -> String.valueOf(OPERATING));
        registry.add("commerce.coupon.waiting.admission-duration", () -> ADMISSION_DURATION.toMillis() + "ms");
    }

    @TestConfiguration
    static class FaultConfig {
        @Bean
        @Primary
        HoldingWaitingRepository holdingWaitingRepository(CouponAdmissionRedisRepository redisRepository) {
            return HoldingWaitingRepository.real(redisRepository);
        }

        @Bean
        ServerSettlement serverSettlement(HoldingWaitingRepository holdingWaitingRepository) {
            return new ServerSettlement(holdingWaitingRepository);
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
    private HoldingWaitingRepository holding;
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
    private OwnedWaitingKeys ownedKeys;

    @BeforeEach
    void setUp() {
        settlement.requireNotWithheld();
        CouponRedisTarget.verify(redisProperties, master);
        ownedKeys = new OwnedWaitingKeys(master, OPERATING);
        ownedKeys.claim(); // 대상 확인과 별개로, 키가 모두 비었을 때만 정리 대상으로 소유한다.
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
        if (ownedKeys != null) {
            ownedKeys.release();
        }
        databaseCleanUp.truncateAllTables();
    }

    /** 응답을 읽지 않는 raw 요청과 그 미확인 등록 key. 소켓은 호출자가 닫는다. */
    private record RawRequest(Socket socket, String key) {
    }

    /**
     * 응답을 읽지 않는 raw HTTP/1.1 발급 요청. 연결 전에 미확인으로 등록하고, 연결·전송에 실패하면 만든 소켓을 닫고 예외를 던진다.
     * 이 경우에도 등록은 남아 정리를 보류한다(요청이 서버에 닿았는지 모른다).
     */
    private RawRequest sendIssue(Long userId, String waitingId) throws IOException {
        String key = settlement.registerRaw("raw POST issue user " + userId + " waitingId " + waitingId);
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/v1/coupon-campaigns/" + OPERATING + "/issues HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "X-USER-ID: " + userId + "\r\n"
                + CouponV1Controller.WAITING_ID_HEADER + ": " + waitingId + "\r\n"
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

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(Long userId, String waitingId) {
        HttpHeaders headers = userHeaders(userId);
        if (waitingId != null) {
            headers.set(CouponV1Controller.WAITING_ID_HEADER, waitingId);
        }
        return settlement.observe("POST issue user " + userId, () -> rest.exchange(
            "/api/v1/coupon-campaigns/" + OPERATING + "/issues", HttpMethod.POST, new HttpEntity<>(null, headers), COUPON));
    }

    private String admit(Long userId) {
        var registered = settlement.observe("POST waiting user " + userId, () -> rest.exchange(
            "/api/v1/coupon-campaigns/" + OPERATING + "/waiting", HttpMethod.POST, new HttpEntity<>(null, userHeaders(userId)), WAITING));
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return registered.getBody().data().waitingId();
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.WaitingResponse>> waiting(Long userId, HttpMethod method) {
        return settlement.observe(method + " waiting user " + userId, () -> rest.exchange(
            "/api/v1/coupon-campaigns/" + OPERATING + "/waiting", method, new HttpEntity<>(null, userHeaders(userId)), WAITING));
    }

    private static HttpHeaders userHeaders(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return headers;
    }

    private Set<String> processing() {
        return master.opsForSet().members("waiting:processing:v1:" + OPERATING);
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

    @DisplayName("Q6: commit·DB 결과 확인 뒤 finish 전에 클라이언트 연결을 끊어 201 을 받지 못하게 해도, 서버는 끝까지 처리하고 재요청은 200·같은 couponId·추가 효과 0 이다.")
    @Test
    void responseLostAfterCommit() throws Exception {
        fixture.operatingCampaign(OPERATING, 5);
        Long user = fixture.user();
        String waitingId = admit(user);
        assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(0, 1));
        holding.holdNextFinishes(1, DEADLINE);

        RawRequest raw = sendIssue(user, waitingId);
        Socket socket = raw.socket();
        try {
            assertThat(holding.awaitFinishHeld(DEADLINE)).as("쓰기 TX commit 뒤·finish 전 서버가 멈춰야 한다").isTrue();
            assertThat(holding.finishHeldWaitingIds()).as("이 raw 요청 하나만 finish 직전에 있다").containsExactly(waitingId);
            Long committedId = storedCouponId(user, OPERATING);
            assertAll(
                () -> assertThat(committedId).as("최초 요청의 발급이 commit 됐다").isNotNull(),
                () -> assertThat(fixture.issuedCount(OPERATING)).isEqualTo(1),
                () -> assertThat(processing()).as("finish 전이라 자리를 아직 점유").containsExactly(String.valueOf(user))
            );
        } finally {
            abort(socket); // 응답 바이트를 하나도 읽지 않고 RST 로 끊는다.
            holding.letFinishesThrough(1);
        }
        assertThat(holding.awaitFinished(1, DEADLINE)).as("서버 측 finish 관찰 = Facade 처리 종료").isTrue();
        settlement.settleRaw(raw.key(), waitingId); // 이 요청의 finish 관찰로만 해소한다.

        Long committedId = storedCouponId(user, OPERATING);
        var retry = issue(user, null);
        System.out.printf("[coupon-q6] campaign=%d user=%d committedCouponId=%d clientReadBytes=0 retry=%s retryCouponId=%d issued=%d coupons=%d finishes=%s%n",
            OPERATING, user, committedId, retry.getStatusCode(), retry.getBody().data().couponId(),
            fixture.issuedCount(OPERATING), fixture.couponCount(OPERATING), holding.finishes());
        assertAll(
            () -> assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(retry.getBody().data().couponId()).isEqualTo(committedId),
            () -> assertThat(fixture.issuedCount(OPERATING)).as("재요청의 추가 발급 효과 없음").isEqualTo(1),
            () -> assertThat(fixture.couponCount(OPERATING)).isEqualTo(1),
            () -> assertThat(holding.finishes()).as("재요청은 입장 권한을 쓰지 않는다").hasSize(1),
            () -> assertThat(processing()).isEmpty()
        );
    }

    @DisplayName("Q11: 쓰기 TX 의 UPDATE 뒤 서버가 머무는 동안 client timeout·연결 종료가 나도 서버 TX 는 살아 있고 자리를 미리 반환하지 않으며, 놓은 뒤 commit·finish 까지 끝난다.")
    @Test
    void clientTimeoutAndDisconnectDoNotEndServerWork() throws Exception {
        fixture.operatingCampaign(OPERATING, 5);
        Long user = fixture.user();
        String waitingId = admit(user);
        assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(0, 1));
        reserveHold.armOnce(DEADLINE);

        RawRequest raw = sendIssue(user, waitingId);
        Socket socket = raw.socket();
        long openWhileHeld;
        Set<String> processingWhileHeld;
        try {
            assertThat(reserveHold.awaitReached(DEADLINE)).as("실제 UPDATE 뒤 쓰기 TX 안에서 멈춰야 한다").isTrue();
            socket.setSoTimeout((int) CLIENT_TIMEOUT.toMillis());
            assertThatThrownBy(() -> socket.getInputStream().read()).as("실제 client read timeout")
                .isInstanceOf(SocketTimeoutException.class);
            abort(socket); // 연결 종료(RST)

            Thread.sleep(300);
            openWhileHeld = openWriteTransactions();
            System.out.println("[coupon-q11] trxWhileHeld=" + describeTransactions());
            processingWhileHeld = processing();
            assertAll(
                () -> assertThat(reserveHold.isHolding()).as("서버 처리 스레드가 아직 barrier 에 있다").isTrue(),
                () -> assertThat(openWhileHeld).as("UPDATE 를 실행한 쓰기 TX 가 열려 있다").isEqualTo(1),
                () -> assertThat(fixture.issuedCount(OPERATING)).as("다른 연결에서는 commit 전 값").isZero(),
                () -> assertThat(processingWhileHeld).as("PROCESSING 점유가 남아 있다").containsExactly(String.valueOf(user)),
                () -> assertThat(holding.finishes()).as("조기 반환 없음").isEmpty()
            );
        } finally {
            if (!socket.isClosed()) {
                abort(socket);
            }
            reserveHold.letGo();
        }

        assertThat(holding.awaitFinished(1, DEADLINE)).as("놓은 뒤 서버 처리 종료(finish) 관찰").isTrue();
        assertThat(holding.startedWaitingIds()).as("이 raw 요청 하나만 start 했다").containsExactly(waitingId);
        settlement.settleRaw(raw.key(), waitingId);
        // innodb_trx 는 최대 0.1초 주기로 갱신되는 캐시라 commit 직후에는 이전 TX 가 보일 수 있다. 유한하게 다시 읽는다.
        long pollStart = System.nanoTime();
        long polled = openWriteTransactions();
        while (polled != 0 && System.nanoTime() - pollStart < Duration.ofSeconds(10).toNanos()) {
            Thread.sleep(100);
            polled = openWriteTransactions();
        }
        long openAfter = polled;
        long polledMs = Duration.ofNanos(System.nanoTime() - pollStart).toMillis();
        Long couponId = storedCouponId(user, OPERATING);
        System.out.printf("[coupon-q11] campaign=%d user=%d clientTimeoutMs=%d openWriteTrxWhileHeld=%d processingWhileHeld=%d openWriteTrxAfter=%d (polledMs=%d) issued=%d couponId=%d finishes=%s%n",
            OPERATING, user, CLIENT_TIMEOUT.toMillis(), openWhileHeld, processingWhileHeld.size(), openAfter, polledMs,
            fixture.issuedCount(OPERATING), couponId, holding.finishes());
        assertAll(
            () -> assertThat(couponId).as("연결이 끊긴 요청도 서버에서 commit").isNotNull(),
            () -> assertThat(fixture.issuedCount(OPERATING)).isEqualTo(1),
            () -> assertThat(openAfter).isZero(),
            () -> assertThat(holding.finishes()).hasSize(1),
            () -> assertThat(processing()).isEmpty(),
            () -> assertThat(couponFacade.status()).isTrue()
        );
    }

    @DisplayName("Q12: C=3 자리를 실제 HTTP 처리 세 건이 PROCESSING 으로 쥔 채 입장 기한(2초)보다 긴 5초가 지나도 회수되지 않고 TTL 도 없으며, 넷째는 승격되지 못해 409 다. 놓으면 셋 다 201 이고 다음 입장 처리에서 넷째가 승격된다.")
    @Test
    void elapsedTimeDoesNotFreeProcessing() throws Exception {
        fixture.operatingCampaign(OPERATING, 10);
        List<Long> users = fixture.users(4);
        List<String> ids = new ArrayList<>();
        for (Long user : users) {
            ids.add(admit(user));
        }
        assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(0, 3));
        holding.holdNextStarted(3, DEADLINE.plus(ELAPSED));
        ExecutorService executor = workers.start(3);
        List<Future<ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>>>> futures = new ArrayList<>();
        long ttl;
        Optional<AdmissionProgress> afterElapsed;
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> fourth;
        long waitedMs;
        try {
            for (int i = 0; i < 3; i++) {
                Long user = users.get(i);
                String waitingId = ids.get(i);
                futures.add(executor.submit(() -> issue(user, waitingId)));
            }
            assertThat(holding.awaitHeld(DEADLINE)).isTrue();
            long start = System.nanoTime();
            Thread.sleep(ELAPSED.toMillis());
            waitedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            afterElapsed = couponFacade.processAdmissions();
            ttl = master.getExpire("waiting:processing:v1:" + OPERATING);
            Set<String> processingAfter = processing();
            fourth = issue(users.get(3), ids.get(3));
            assertAll(
                () -> assertThat(afterElapsed).as("PROCESSING 미회수·빈자리 없음").contains(new AdmissionProgress(0, 0)),
                () -> assertThat(ttl).isEqualTo(-1L),
                () -> assertThat(processingAfter).hasSize(3),
                () -> assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(fourth.getBody().meta().errorCode()).isEqualTo("COUPON_WAITING_NOT_READY"),
                () -> assertThat(fixture.issuedCount(OPERATING)).isZero()
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
        assertThat(holding.awaitFinished(3, DEADLINE)).isTrue();
        Optional<AdmissionProgress> afterFinish = couponFacade.processAdmissions();
        System.out.printf("[coupon-q12] campaign=%d C=3 waitedMs=%d durationMs=%d processingTtl=%d afterElapsed=%s fourth=%s heldStatuses=%s issued=%d afterFinish=%s%n",
            OPERATING, waitedMs, ADMISSION_DURATION.toMillis(), ttl, afterElapsed, fourth.getStatusCode(), statuses,
            fixture.issuedCount(OPERATING), afterFinish);
        assertAll(
            () -> assertThat(statuses).containsOnly(HttpStatus.CREATED),
            () -> assertThat(fixture.issuedCount(OPERATING)).isEqualTo(3),
            () -> assertThat(processing()).isEmpty(),
            () -> assertThat(afterFinish).as("넷째 승격").contains(new AdmissionProgress(0, 1))
        );
    }

    @DisplayName("WQ4: 입장 기한이 지나면 발급은 409 EXPIRED 이고, 회수 전 재등록은 200 EXPIRED 안내(같은 ID·무변경), 회수 뒤 재등록은 201 새 ID 다.")
    @Test
    void readyExpiresOverHttp() throws Exception {
        fixture.operatingCampaign(OPERATING, 5);
        Long user = fixture.user();
        String waitingId = admit(user);
        assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(0, 1));
        long until = System.nanoTime() + DEADLINE.toNanos();
        while (waiting(user, HttpMethod.GET).getBody().data().status() != CouponWaitingState.EXPIRED) {
            assertThat(System.nanoTime()).as("기한 경과 대기 초과").isLessThan(until);
            Thread.sleep(100);
        }

        var expired = issue(user, waitingId);
        var notice = waiting(user, HttpMethod.POST);
        assertAll(
            () -> assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
            () -> assertThat(expired.getBody().meta().errorCode()).isEqualTo("COUPON_WAITING_EXPIRED"),
            () -> assertThat(notice.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(notice.getBody().data().status()).isEqualTo(CouponWaitingState.EXPIRED),
            () -> assertThat(notice.getBody().data().waitingId()).isEqualTo(waitingId),
            () -> assertThat(fixture.issuedCount(OPERATING)).isZero(),
            () -> assertThat(holding.finishes()).isEmpty()
        );
        assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(1, 0));
        var renewed = waiting(user, HttpMethod.POST);
        assertAll(
            () -> assertThat(renewed.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(renewed.getBody().data().waitingId()).isNotEqualTo(waitingId)
        );
    }
}
