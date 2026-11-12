package com.loopers.interfaces.api.coupon;

import com.loopers.interfaces.scheduler.CouponWaitingScheduler;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.user.UserFacade;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.fixture.CouponFixture;
import com.loopers.fixture.CouponRedisTarget;
import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.utils.DatabaseCleanUp;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 실제 MVC 체인에서 대기 등록 입구 제한을 낮은 한도(주기 1시간에 3개)로 확인한다(설계 §12.3·12.13 WQ8, §11.4 RL3~RL5).
 * 사례마다 새 컨텍스트로 limiter 잔량을 분리한다. 한 주기 안에서 끝나는 짧은 사례이며 다른 테스트의 허가 잔량에 의존하지 않는다.
 * 요청자 확인(UserFacade)·CouponFacade 호출은 spy 로, Redis 변경은 운영 캠페인 키 내용으로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@DisplayName("대기 등록 한도를 넘으면 사용자 확인·Redis 전에 429 이고, 사용자·캠페인이 한도를 공유하며, 발급·조회·목록·제어는 이 한도로 막지 않는다.")
class CouponWaitingRateLimitE2ETest {

    private static final long OPERATING = 9_004L;

    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.WaitingResponse>> WAITING =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> COUPON =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>>> PAGE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<CouponAdmissionDto.AdmissionResponse>> ADMISSION =
        new ParameterizedTypeReference<>() {};

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
        registry.add("commerce.coupon.waiting.campaign-id", () -> String.valueOf(OPERATING));
        registry.add("commerce.coupon.rate-limit.limit-for-period", () -> "3");
        registry.add("commerce.coupon.rate-limit.refresh-period", () -> "1h");
    }

    /** 자동 입장 회차가 테스트의 수동 processAdmissions 와 경쟁하지 않게 이 컨텍스트에서만 대체한다(실제 회차는 CouponWaitingScheduler*Test). */
    @MockitoBean
    private CouponWaitingScheduler couponWaitingScheduler;

    @Autowired
    private TestRestTemplate rest;
    @MockitoSpyBean
    private UserFacade userFacade;
    @MockitoSpyBean
    private CouponFacade couponFacade;
    @Autowired
    private CouponFixture fixture;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> master;

    private OwnedWaitingKeys ownedKeys;

    @BeforeEach
    void setUp() {
        CouponRedisTarget.verify(redisProperties, master);
        ownedKeys = new OwnedWaitingKeys(master, OPERATING);
        ownedKeys.claim(); // 대상 확인과 별개로, 키가 모두 비었을 때만 정리 대상으로 소유한다.
        fixture.operatingCampaign(OPERATING, 10);
        couponFacade.open();
    }

    @AfterEach
    void tearDown() {
        couponFacade.close();
        if (ownedKeys != null) {
            ownedKeys.release();
        }
        databaseCleanUp.truncateAllTables();
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, String userHeader, String waitingId,
                                       ParameterizedTypeReference<T> type) {
        HttpHeaders headers = new HttpHeaders();
        if (userHeader != null) {
            headers.set("X-USER-ID", userHeader);
        }
        if (waitingId != null) {
            headers.set(CouponV1Controller.WAITING_ID_HEADER, waitingId);
        }
        return rest.exchange(path, method, new HttpEntity<>(null, headers), type);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.WaitingResponse>> register(Object user, Object campaignId) {
        return call(HttpMethod.POST, "/api/v1/coupon-campaigns/" + campaignId + "/waiting", user == null ? null : String.valueOf(user),
            null, WAITING);
    }

    private static String errorCode(ResponseEntity<? extends ApiResponse<?>> response) {
        return response.getBody().meta().errorCode();
    }

    private Map<String, Object> waitingState() {
        Map<String, Object> state = new TreeMap<>();
        for (String key : CouponAdmissionRedisRepository.waitingKeys(OPERATING)) {
            DataType type = master.type(key);
            state.put(key, switch (type) {
                case HASH -> new TreeMap<>(master.opsForHash().entries(key));
                case ZSET -> String.valueOf(master.opsForZSet().rangeWithScores(key, 0, -1).stream()
                    .map(t -> t.getValue() + "=" + t.getScore()).toList());
                case SET -> new java.util.TreeSet<>(master.opsForSet().members(key));
                default -> type.code();
            });
        }
        return state;
    }

    @DisplayName("한도 3 을 다 쓰면(실패한 등록도 허가를 쓴다) 다른 사용자·다른 캠페인·사용자 헤더 없음·숫자 아닌 경로도 429 이며, 사용자 확인·Facade·Redis 를 부르지 않고 기존 순번은 그대로다.")
    @Test
    void exhaustedRejectsBeforeResolverAndShares() {
        List<Long> users = fixture.users(3);
        var first = register(users.get(0), OPERATING);
        var second = register(users.get(1), OPERATING);
        var unknownUser = register(999_999L, OPERATING);
        Map<String, Object> before = waitingState();
        clearInvocations(userFacade, couponFacade);

        var otherUser = register(users.get(2), OPERATING);
        var otherCampaign = register(users.get(0), 12_345L);
        var noUserHeader = register(null, OPERATING);
        var badPath = register(users.get(0), "abc");
        var again = register(users.get(0), OPERATING);

        assertAll(
            () -> assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(errorCode(unknownUser)).as("허가를 쓴 뒤 사용자 확인 404").isEqualTo("USER_NOT_FOUND"),
            () -> assertThat(List.of(otherUser, otherCampaign, noUserHeader, badPath, again)).allSatisfy(r -> {
                assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(r.getBody().meta().errorCode()).isEqualTo("COUPON_RATE_LIMITED");
                assertThat(r.getBody().meta().result()).isEqualTo(ApiResponse.Metadata.Result.FAIL);
            }),
            () -> verify(userFacade, never()).requireExists(anyLong()),
            () -> verify(couponFacade, never()).registerWaiting(anyLong(), anyLong()),
            () -> assertThat(waitingState()).as("거절 요청은 대기 상태를 바꾸지 않는다").isEqualTo(before),
            () -> assertThat(call(HttpMethod.GET, "/api/v1/coupon-campaigns/" + OPERATING + "/waiting", String.valueOf(users.get(0)), null, WAITING)
                .getBody().data()).as("기존 ID·순번 불변").isEqualTo(first.getBody().data())
        );
    }

    @DisplayName("한도를 다 쓴 동안에도 유효 READY 의 발급·대기 조회·목록·수용 제어는 이 한도로 막히지 않고, 등록은 계속 429 다(허가 반환 없음).")
    @Test
    void otherPathsNotLimited() {
        List<Long> users = fixture.users(3);
        String waitingId = null;
        for (Long user : users) {
            var registered = register(user, OPERATING);
            assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            if (waitingId == null) {
                waitingId = registered.getBody().data().waitingId();
            }
        }
        assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(0, 3));
        String user = String.valueOf(users.get(0));

        var issued = call(HttpMethod.POST, "/api/v1/coupon-campaigns/" + OPERATING + "/issues", user, waitingId, COUPON);
        var stored = call(HttpMethod.POST, "/api/v1/coupon-campaigns/" + OPERATING + "/issues", user, null, COUPON);
        var query = call(HttpMethod.GET, "/api/v1/coupon-campaigns/" + OPERATING + "/waiting", String.valueOf(users.get(1)), null, WAITING);
        var list = call(HttpMethod.GET, "/api/v1/coupons", user, null, PAGE);
        var status = call(HttpMethod.GET, "/api/coupon/admission", null, null, ADMISSION);
        var close = call(HttpMethod.POST, "/api/coupon/admission/close", null, null, ADMISSION);
        var open = call(HttpMethod.POST, "/api/coupon/admission/open", null, null, ADMISSION);
        var stillLimited = register(users.get(0), OPERATING);

        assertAll(
            () -> assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(stored.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(query.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(list.getBody().data().items()).hasSize(1),
            () -> assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK),
            () -> assertThat(close.getBody().data().accepting()).isFalse(),
            () -> assertThat(open.getBody().data().accepting()).isTrue(),
            () -> assertThat(errorCode(stillLimited)).isEqualTo("COUPON_RATE_LIMITED")
        );
    }
}
