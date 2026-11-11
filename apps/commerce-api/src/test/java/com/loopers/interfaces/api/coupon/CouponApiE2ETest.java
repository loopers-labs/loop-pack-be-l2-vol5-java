package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.HoldingAdmissionRepository;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository.Acquisition;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.fixture.CouponFixture;
import com.loopers.fixture.CouponRedisTarget;
import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import com.loopers.infrastructure.coupon.WorkerTermination;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 쿠폰 고객·제어 API 를 실제 HTTP·MySQL·fixture Redis 컨테이너로 확인한다(Q1·Q2·Q7·Q14·Q15, 업무 거절·수용 거절 우선순위).
 * 수용 저장소는 실제 Redis 저장소에 위임하는 test decorator 를 @Primary 로 두어, Q7 에서만 실제 ACQUIRED 직후·쓰기 TX 전 붙잡기를 켠다.
 * 다른 사례에서는 그대로 통과한다. 앱 수용 상태는 같은 bean 이므로 사례마다 명시적으로 열고, 끝나면 닫는다.
 *
 * <p>정리 순서: 붙잡기 해제 → 클라이언트 worker 종료 확인 → 서버 발급/TX 종료 근거 확인({@link ServerSettlement}) →
 * 이 테스트가 보낸 token 만 SREM → DB 정리. 클라이언트 종료와 서버 종료는 다른 근거이며, 서버 근거가 없으면 정리를 보류하고
 * 원래 실패와 미확인 사유를 남긴다. 보류 상태는 컨텍스트 bean({@link ServerSettlement})에 남아, 같은 컨텍스트의 이후 사례가
 * 키 검사·준비·수용 열기 전에 실패하고 그 사례의 정리도 하지 않게 한다.
 * Q6 의 실제 응답 유실과 Q11 의 실제 HTTP timeout 은 이 테스트의 범위가 아니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// fixture Redis 주소 주입 등으로 이 클래스만의 컨텍스트가 생긴다. 끝나면 닫아 MySQL 연결 풀을 반납한다(누적 시 Too many connections).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("쿠폰 API 는 최초 201·기존 200 을 같은 쿠폰으로 돌려주고, 수용 C 를 넘는 신규 요청을 DB 전에 거절하며, 제어 API 로 수용을 다룬다.")
class CouponApiE2ETest {

    private static final String KEY = CouponAdmissionRedisRepository.PERMITS_KEY;
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);
    private static final Duration SETTLE_DEADLINE = Duration.ofSeconds(10);

    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> COUPON =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>>> PAGE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<CouponAdmissionDto.AdmissionResponse>> ADMISSION =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<CouponAdmissionDto.CleanupResponse>> CLEANUP =
        new ParameterizedTypeReference<>() {};

    @DynamicPropertySource
    static void pinToFixtureContainer(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
    }

    @TestConfiguration
    static class HoldingConfig {
        @Bean
        @Primary
        HoldingAdmissionRepository holdingAdmissionRepository(CouponAdmissionRedisRepository redisRepository) {
            return new HoldingAdmissionRepository(redisRepository);
        }

        /** 정리 보류 상태를 컨텍스트 수명 동안 유지하려고 bean 으로 둔다. */
        @Bean
        ServerSettlement serverSettlement(HoldingAdmissionRepository holdingAdmissionRepository) {
            return new ServerSettlement(holdingAdmissionRepository);
        }
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CouponFacade couponFacade;
    @Autowired
    private HoldingAdmissionRepository holding;
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

    @Autowired
    private ServerSettlement settlement;

    private final WorkerTermination workers = new WorkerTermination();
    private boolean targetVerified;

    @BeforeEach
    void setUp() {
        settlement.requireNotWithheld(); // 앞 사례가 정리를 보류했으면 키 검사·준비·수용 열기 전에 멈춘다.
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
        couponFacade.close();
        settlement.confirmCleanupOrWithhold(workers.safeToClean(), SETTLE_DEADLINE); // 보류면 예외로 끝나 아래 정리를 하지 않는다.
        if (targetVerified && !holding.sentTokens().isEmpty()) {
            master.opsForSet().remove(KEY, holding.sentTokens().toArray());
        }
        databaseCleanUp.truncateAllTables();
        if (targetVerified) {
            assertThat(master.type(KEY)).as("자기 token 정리 뒤 전용 키가 비어야 한다").isEqualTo(DataType.NONE);
        }
    }

    private HttpEntity<Object> as(Long userId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-USER-ID", String.valueOf(userId));
        }
        return new HttpEntity<>(null, headers);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(Long userId, Object campaignId) {
        return settlement.observe("POST issue user " + userId + " campaign " + campaignId, () ->
            rest.exchange("/api/v1/coupon-campaigns/" + campaignId + "/issues", HttpMethod.POST, as(userId), COUPON));
    }

    private ResponseEntity<ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>>> list(Long userId, String query) {
        return settlement.observe("GET coupons user " + userId, () ->
            rest.exchange("/api/v1/coupons" + query, HttpMethod.GET, as(userId), PAGE));
    }

    /** 서버 실행이 아닌, 테스트가 직접 넣는 점유 token. 정리 판단에서 서버 실행과 구분한다. */
    private String testToken() {
        String token = "coupon-e2e-own-" + UUID.randomUUID();
        settlement.markTestToken(token);
        return token;
    }

    private static String errorCode(ResponseEntity<? extends ApiResponse<?>> response) {
        return response.getBody().meta().errorCode();
    }

    @Nested
    @DisplayName("POST /api/v1/coupon-campaigns/{campaignId}/issues")
    class Issue {

        @DisplayName("Q1·Q2: 최초 201 과 재요청 200 이 같은 다섯 필드·같은 couponId 이고, 쿠폰 1·발급 수 1·추가 효과 0 이며 재요청은 Redis 를 쓰지 않는다.")
        @Test
        void createdThenStored() {
            Long user = fixture.user();
            Long campaignId = fixture.activeCampaign("가을 감사", 10, CouponBenefitType.FIXED_AMOUNT, 5_000L);

            var first = issue(user, campaignId);
            int sentAfterFirst = holding.sentTokens().size();
            var second = issue(user, campaignId);

            CouponV1Dto.CouponResponse created = first.getBody().data();
            assertAll(
                () -> assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(created.couponId()).isNotNull(),
                () -> assertThat(created.campaignId()).isEqualTo(campaignId),
                () -> assertThat(created.campaignName()).isEqualTo("가을 감사"),
                () -> assertThat(created.benefitType()).isEqualTo(CouponBenefitType.FIXED_AMOUNT),
                () -> assertThat(created.benefitValue()).isEqualTo(5_000L),
                () -> assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(second.getBody().data()).isEqualTo(created),
                () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(1),
                () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(1),
                () -> assertThat(holding.sentTokens()).as("재요청은 신규 수용 자리를 쓰지 않는다").hasSize(sentAfterFirst),
                () -> assertThat(holding.releasedTokens()).hasSize(1),
                () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE)
            );
        }

        @DisplayName("Q2: 비활성·품절·수용 닫힘 뒤에도 기존 쿠폰은 200·같은 ID 이고, 이름은 현재 캠페인 값·혜택은 저장된 과거 값이다.")
        @Test
        void storedWinsOverInactiveSoldOutClosed() {
            Long user = fixture.user();
            Long campaignId = fixture.activeCampaign("처음 이름", 3, CouponBenefitType.FIXED_AMOUNT, 5_000L);
            Long couponId = issue(user, campaignId).getBody().data().couponId();
            fixture.rename(campaignId, "바뀐 이름");
            fixture.changeBenefit(campaignId, CouponBenefitType.PERCENTAGE, 10L);

            jdbcTemplate.update("UPDATE coupon_campaign SET status = 'INACTIVE' WHERE id = ?", campaignId);
            var inactive = issue(user, campaignId);
            jdbcTemplate.update("UPDATE coupon_campaign SET status = 'ACTIVE', issued_count = total_quota WHERE id = ?", campaignId);
            var soldOut = issue(user, campaignId);
            couponFacade.close();
            var closed = issue(user, campaignId);

            assertAll(
                () -> assertThat(List.of(inactive, soldOut, closed)).allSatisfy(r -> {
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(r.getBody().data().couponId()).isEqualTo(couponId);
                    assertThat(r.getBody().data().campaignName()).isEqualTo("바뀐 이름");
                    assertThat(r.getBody().data().benefitType()).isEqualTo(CouponBenefitType.FIXED_AMOUNT);
                    assertThat(r.getBody().data().benefitValue()).isEqualTo(5_000L);
                }),
                () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(1),
                () -> assertThat(holding.releasedTokens()).as("최초 발급 한 번만 수용 자리를 썼다").hasSize(1)
            );
        }

        @DisplayName("캠페인 없음 404·비활성 409·품절 409 는 쿠폰을 만들지 않고, 판별 뒤 token 을 반환한다.")
        @Test
        void businessRejections() {
            Long user = fixture.user();
            Long inactive = fixture.inactiveCampaign(5);
            Long soldOut = fixture.soldOutCampaign(5);

            var missing = issue(user, 999_999L);
            var inactiveResponse = issue(user, inactive);
            var soldOutResponse = issue(user, soldOut);

            assertAll(
                () -> assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(errorCode(missing)).isEqualTo("COUPON_CAMPAIGN_NOT_FOUND"),
                () -> assertThat(inactiveResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(errorCode(inactiveResponse)).isEqualTo("COUPON_CAMPAIGN_INACTIVE"),
                () -> assertThat(soldOutResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(errorCode(soldOutResponse)).isEqualTo("COUPON_CAMPAIGN_SOLD_OUT"),
                () -> assertThat(fixture.couponCount(inactive) + fixture.couponCount(soldOut)).isZero(),
                () -> assertThat(fixture.issuedCount(inactive)).isZero(),
                () -> assertThat(fixture.issuedCount(soldOut)).isEqualTo(5),
                () -> assertThat(holding.releasedTokens()).hasSize(3),
                () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE),
                () -> assertThat(couponFacade.status()).isTrue()
            );
        }

        @DisplayName("요청자 식별 오류는 기존 계약 그대로다: 헤더 없음 400 INVALID_REQUEST, 없는 사용자 404 USER_NOT_FOUND.")
        @Test
        void identification() {
            Long campaignId = fixture.activeCampaign(5);

            var noHeader = issue(null, campaignId);
            var unknown = issue(999_999L, campaignId);

            assertAll(
                () -> assertThat(noHeader.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(errorCode(noHeader)).isEqualTo("INVALID_REQUEST"),
                () -> assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(errorCode(unknown)).isEqualTo("USER_NOT_FOUND"),
                () -> assertThat(holding.sentTokens()).isEmpty()
            );
        }

        @DisplayName("수용 거절이 캠페인 판단보다 먼저다: 닫힘이면 없는 캠페인도 503(Redis 미호출), 자리가 없으면 429.")
        @Test
        void admissionBeforeCampaign() {
            Long user = fixture.user();
            couponFacade.close();
            var closed = issue(user, 999_999L);
            assertAll(
                () -> assertThat(closed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
                () -> assertThat(errorCode(closed)).isEqualTo("COUPON_ADMISSION_UNAVAILABLE"),
                () -> assertThat(holding.sentTokens()).isEmpty()
            );

            couponFacade.open();
            for (int i = 0; i < 3; i++) {
                assertThat(holding.tryAcquire(testToken(), 3)).isEqualTo(Acquisition.ACQUIRED);
            }
            var full = issue(user, 999_999L);
            assertAll(
                () -> assertThat(full.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS),
                () -> assertThat(errorCode(full)).isEqualTo("COUPON_ADMISSION_FULL"),
                () -> assertThat(couponFacade.status()).as("FULL 은 수용을 닫지 않는다").isTrue()
            );
        }
    }

    @DisplayName("Q7: 실제 Redis C=3 에서 세 요청을 ACQUIRED 직후·쓰기 TX 전에 붙잡으면 넷째는 429·DB 미진입이고, 하나가 반환된 뒤 재요청이 수용되며 최종 쿠폰 4·발급 수 4·token 0 이다.")
    @Test
    void capacityThreeHoldsThenReadmits() throws Exception {
        Long campaignId = fixture.activeCampaign(10);
        List<Long> users = fixture.users(4);
        holding.holdNextAcquired(3, DEADLINE);
        ExecutorService executor = workers.start(3);
        List<Future<ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>>>> held = new ArrayList<>();
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> fourthRejected;
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> fourthAdmitted;
        List<String> heldTokens;
        try {
            for (int i = 0; i < 3; i++) {
                Long user = users.get(i);
                held.add(executor.submit(() -> issue(user, campaignId)));
            }
            assertThat(holding.awaitHeld(DEADLINE)).as("세 요청이 실제 ACQUIRED 뒤 붙잡혀야 한다").isTrue();
            heldTokens = holding.heldTokens();
            assertAll(
                () -> assertThat(master.opsForSet().members(KEY)).containsExactlyInAnyOrderElementsOf(heldTokens),
                () -> assertThat(fixture.issuedCount(campaignId)).isZero(),
                () -> assertThat(fixture.couponCount(campaignId)).isZero()
            );

            fourthRejected = issue(users.get(3), campaignId);
            assertAll(
                () -> assertThat(fourthRejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS),
                () -> assertThat(errorCode(fourthRejected)).isEqualTo("COUPON_ADMISSION_FULL"),
                () -> assertThat(fixture.issuedCount(campaignId)).as("429 는 DB 발급 경로 미진입").isZero(),
                () -> assertThat(fixture.couponCount(campaignId, users.get(3))).isZero(),
                () -> assertThat(master.opsForSet().size(KEY)).isEqualTo(3)
            );

            holding.letThrough(1);
            assertThat(holding.awaitReleases(1, DEADLINE)).as("한 실행의 서버 측 반환 관찰").isTrue();
            assertThat(master.opsForSet().size(KEY)).isEqualTo(2);

            fourthAdmitted = issue(users.get(3), campaignId);
            assertThat(fourthAdmitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            holding.letThrough(2);
        } finally {
            holding.stopHolding();
            workers.close(executor, TERMINATION_DEADLINE);
        }
        List<HttpStatus> heldStatuses = new ArrayList<>();
        for (var future : held) {
            heldStatuses.add(HttpStatus.valueOf(future.get(0, TimeUnit.MILLISECONDS).getStatusCode().value()));
        }
        assertThat(holding.awaitReleases(4, DEADLINE)).isTrue();

        System.out.printf("[coupon-q7] campaign=%d C=3 held=%d fourthFirst=%s fourthRetry=%s heldStatuses=%s issued=%d coupons=%d "
                + "releases=%d keyType=%s%n", campaignId, heldTokens.size(), fourthRejected.getStatusCode(),
            fourthAdmitted.getStatusCode(), heldStatuses, fixture.issuedCount(campaignId), fixture.couponCount(campaignId),
            holding.releasedTokens().size(), master.type(KEY));
        assertAll(
            () -> assertThat(heldStatuses).containsOnly(HttpStatus.CREATED),
            () -> assertThat(fixture.couponCount(campaignId)).isEqualTo(4),
            () -> assertThat(fixture.issuedCount(campaignId)).isEqualTo(4),
            () -> assertThat(holding.releasedTokens()).hasSize(4).doesNotHaveDuplicates(),
            () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE),
            () -> assertThat(couponFacade.status()).isTrue()
        );
    }

    @Nested
    @DisplayName("GET /api/v1/coupons")
    class MyCoupons {

        @DisplayName("Q1·Q15: 본인 쿠폰만 저장 시각·ID 로 안정 정렬해 주고, createdAt 은 DB 저장 시각의 UTC·소수 6자리·Z 다.")
        @Test
        void ownCouponsOnly() {
            Long me = fixture.user();
            Long other = fixture.user();
            Long a = fixture.activeCampaign("A", 5, CouponBenefitType.FIXED_AMOUNT, 1_000L);
            Long b = fixture.activeCampaign("B", 5, CouponBenefitType.PERCENTAGE, 15L);
            Long c = fixture.activeCampaign("C", 5, CouponBenefitType.FIXED_AMOUNT, 3_000L);
            Long ca = issue(me, a).getBody().data().couponId();
            Long cb = issue(me, b).getBody().data().couponId();
            Long cc = issue(me, c).getBody().data().couponId();
            Long othersCoupon = issue(other, a).getBody().data().couponId();
            fixture.setCouponCreatedAt(ca, "2026-11-10 09:30:00.123456");
            fixture.setCouponCreatedAt(cb, "2026-11-10 09:30:00.500000");
            fixture.setCouponCreatedAt(cc, "2026-11-10 09:30:00.500000");
            fixture.rename(b, "B 새 이름");

            var latest = list(me, "");
            var oldest = list(me, "?sort=oldest");
            var again = list(me, "");

            List<CouponV1Dto.OwnedCouponResponse> items = latest.getBody().data().items();
            assertAll(
                () -> assertThat(latest.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(latest.getBody().data().totalElements()).isEqualTo(3),
                () -> assertThat(items).extracting(CouponV1Dto.OwnedCouponResponse::couponId).containsExactly(cc, cb, ca)
                    .doesNotContain(othersCoupon),
                () -> assertThat(oldest.getBody().data().items()).extracting(CouponV1Dto.OwnedCouponResponse::couponId)
                    .containsExactly(ca, cb, cc),
                () -> assertThat(items.get(2).createdAt()).isEqualTo("2026-11-10T09:30:00.123456Z"),
                () -> assertThat(items.get(2).createdAt()).isEqualTo(fixture.storedCreatedAt(ca) + "Z"),
                () -> assertThat(items.get(1).campaignName()).isEqualTo("B 새 이름"),
                () -> assertThat(items.get(1).benefitType()).isEqualTo(CouponBenefitType.PERCENTAGE),
                () -> assertThat(items.get(1).benefitValue()).isEqualTo(15L),
                () -> assertThat(again.getBody().data().items()).isEqualTo(items)
            );
        }

        @DisplayName("빈 목록은 200 이고, 페이지·정렬 오류는 기존 공통 오류다.")
        @Test
        void emptyAndInvalidPaging() {
            Long me = fixture.user();

            var empty = list(me, "");
            var zeroSize = list(me, "?size=0");
            var tooLarge = list(me, "?size=101");
            var badSort = list(me, "?sort=popular");

            assertAll(
                () -> assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(empty.getBody().data().items()).isEmpty(),
                () -> assertThat(empty.getBody().data().totalElements()).isZero(),
                () -> assertThat(errorCode(zeroSize)).isEqualTo("INVALID_PAGE_REQUEST"),
                () -> assertThat(errorCode(tooLarge)).isEqualTo("INVALID_PAGE_REQUEST"),
                () -> assertThat(errorCode(badSort)).isEqualTo("INVALID_SORT"),
                () -> assertThat(badSort.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
            );
        }
    }

    @Nested
    @DisplayName("/api/coupon/admission (Q14)")
    class Control {

        private ResponseEntity<ApiResponse<CouponAdmissionDto.AdmissionResponse>> post(String action) {
            return settlement.observe("POST admission " + action, () ->
                rest.exchange("/api/coupon/admission/" + action, HttpMethod.POST, HttpEntity.EMPTY, ADMISSION));
        }

        private ResponseEntity<ApiResponse<CouponAdmissionDto.CleanupResponse>> cleanup(String json) {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return settlement.observe("POST admission cleanup", () ->
                rest.exchange("/api/coupon/admission/cleanup", HttpMethod.POST, new HttpEntity<>(json, headers), CLEANUP));
        }

        private boolean status() {
            return settlement.observe("GET admission", () ->
                    rest.exchange("/api/coupon/admission", HttpMethod.GET, HttpEntity.EMPTY, ADMISSION))
                .getBody().data().accepting();
        }

        @DisplayName("close·open 은 반복 가능하고 현재 값을 돌려주며, GET 은 같은 공유 값을 보여준다. 닫힌 뒤 신규 발급은 503·Redis 미호출이다.")
        @Test
        void closeAndOpen() {
            Long user = fixture.user();
            Long campaignId = fixture.activeCampaign(5);

            assertAll(
                () -> assertThat(post("close").getBody().data().accepting()).isFalse(),
                () -> assertThat(post("close").getBody().data().accepting()).isFalse(),
                () -> assertThat(status()).isFalse(),
                () -> assertThat(couponFacade.status()).isFalse()
            );
            var rejected = issue(user, campaignId);
            assertAll(
                () -> assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
                () -> assertThat(holding.sentTokens()).isEmpty(),
                () -> assertThat(fixture.issuedCount(campaignId)).isZero()
            );
            assertAll(
                () -> assertThat(post("open").getBody().data().accepting()).isTrue(),
                () -> assertThat(status()).isTrue(),
                () -> assertThat(issue(user, campaignId).getStatusCode()).isEqualTo(HttpStatus.CREATED)
            );
        }

        @DisplayName("열린 상태의 cleanup 은 409 이고 Set 을 바꾸지 않는다.")
        @Test
        void cleanupRejectedWhenOpen() {
            String own = testToken();
            holding.tryAcquire(own, 3);

            var response = cleanup("{\"tokens\":[\"" + own + "\"]}");

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(errorCode(response)).isEqualTo("COUPON_ADMISSION_OPERATION_REJECTED"),
                () -> assertThat(master.opsForSet().members(KEY)).containsExactly(own)
            );
        }

        @DisplayName("닫힌 상태의 cleanup 은 지정 token 만 지우고, 반복·없는 token 은 0 이며, 수용을 다시 열지 않는다.")
        @Test
        void cleanupRemovesOnlyGiven() {
            String own = testToken();
            String keep = testToken();
            holding.tryAcquire(own, 3);
            holding.tryAcquire(keep, 3);
            post("close");

            var first = cleanup("{\"tokens\":[\"" + own + "\",\"" + UUID.randomUUID() + "\"]}");
            var repeat = cleanup("{\"tokens\":[\"" + own + "\"]}");

            assertAll(
                () -> assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(first.getBody().data().removedCount()).isEqualTo(1L),
                () -> assertThat(repeat.getBody().data().removedCount()).isZero(),
                () -> assertThat(master.opsForSet().members(KEY)).containsExactly(keep),
                () -> assertThat(status()).isFalse()
            );
        }

        @DisplayName("JSON 매핑 실패는 기존 400 Bad Request, 읽은 뒤의 입력 검증 실패는 400 INVALID_REQUEST 로 구분한다.")
        @Test
        void inputErrors() {
            post("close");

            assertAll(
                () -> assertThat(errorCode(cleanup("{\"tokens\":[]}"))).isEqualTo("INVALID_REQUEST"),
                () -> assertThat(errorCode(cleanup("{\"tokens\":[\"\"]}"))).isEqualTo("INVALID_REQUEST"),
                () -> assertThat(errorCode(cleanup("{\"tokens\":[null]}"))).isEqualTo("INVALID_REQUEST"),
                () -> assertThat(errorCode(cleanup("{}"))).isEqualTo("INVALID_REQUEST"),
                () -> assertThat(errorCode(cleanup("{"))).isEqualTo("Bad Request"),
                () -> assertThat(errorCode(cleanup("{\"tokens\":{\"a\":1}}"))).isEqualTo("Bad Request"),
                () -> assertThat(cleanup("{").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(master.type(KEY)).isEqualTo(DataType.NONE)
            );
        }
    }
}
