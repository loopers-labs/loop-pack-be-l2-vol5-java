package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.HoldingWaitingRepository;
import com.loopers.application.coupon.HoldingWaitingRepository.Finish;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponWaitingState;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 쿠폰 고객·제어 API 를 실제 HTTP·MySQL·fixture Redis 컨테이너로 확인한다(설계 §12.7·12.13 WQ3·WQ5·WQ7·WQ9·WQ10, 기존 Q1·Q2·Q14·Q15).
 * 운영 캠페인 ID 는 시작 속성으로 고정하고(OPERATING) 같은 ID 의 캠페인을 fixture 가 만든다. 자동 입장 스케줄러는 아직 없으므로
 * 입장은 테스트가 Facade.processAdmissions 를 직접 불러 진행한다. 등록 요청률 제한은 이 컨텍스트의 등록 총량보다 충분히 크게 둔다
 * (제한 자체는 CouponWaitingRateLimitE2ETest 가 낮은 한도로 본다).
 *
 * <p>대기실 저장소는 실제 Redis 저장소에 위임하는 test decorator 를 @Primary 로 두어, 필요한 사례에서만 STARTED 직후·쓰기 TX 전 붙잡기를 켠다.
 * 정리 순서: 붙잡기 해제 → 클라이언트 worker 종료 확인 → 서버 발급/TX 종료 근거 확인({@link ServerSettlement}) → 운영 캠페인 대기실 키 삭제 → DB 정리.
 * 서버 근거가 없으면 정리를 보류하고, 보류 상태는 컨텍스트 bean 에 남아 같은 컨텍스트의 이후 사례가 준비 전에 실패한다.
 * 실제 응답 유실·HTTP timeout 은 CouponHttpFaultE2ETest 가 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// fixture Redis 주소 주입 등으로 이 클래스만의 컨텍스트가 생긴다. 끝나면 닫아 MySQL 연결 풀을 반납한다(누적 시 Too many connections).
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("쿠폰 API 는 대기 등록·입장 권한으로 최초 201·기존 200 을 같은 쿠폰으로 돌려주고, 진행 중 202·오류 표를 지키며, 제어 API 로 수용을 다룬다.")
class CouponApiE2ETest {

    private static final long OPERATING = 9_001L;
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);
    private static final Duration SETTLE_DEADLINE = Duration.ofSeconds(10);
    private static final String UTC_MICROS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}000Z";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.CouponResponse>> COUPON =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<CouponV1Dto.WaitingResponse>> WAITING =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>>> PAGE =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<CouponAdmissionDto.AdmissionResponse>> ADMISSION =
        new ParameterizedTypeReference<>() {};

    @DynamicPropertySource
    static void pinToFixtureContainer(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
        registry.add("commerce.coupon.waiting.campaign-id", () -> String.valueOf(OPERATING));
        registry.add("commerce.coupon.rate-limit.limit-for-period", () -> "100000");
    }

    @TestConfiguration
    static class HoldingConfig {
        @Bean
        @Primary
        HoldingWaitingRepository holdingWaitingRepository(CouponAdmissionRedisRepository redisRepository) {
            return HoldingWaitingRepository.real(redisRepository);
        }

        /** 정리 보류 상태를 컨텍스트 수명 동안 유지하려고 bean 으로 둔다. */
        @Bean
        ServerSettlement serverSettlement(HoldingWaitingRepository holdingWaitingRepository) {
            return new ServerSettlement(holdingWaitingRepository);
        }
    }

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private CouponFacade couponFacade;
    @Autowired
    private HoldingWaitingRepository holding;
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
    private OwnedWaitingKeys ownedKeys;

    @BeforeEach
    void setUp() {
        settlement.requireNotWithheld(); // 앞 사례가 정리를 보류했으면 키 검사·준비·수용 열기 전에 멈춘다.
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
        couponFacade.close();
        settlement.confirmCleanupOrWithhold(workers.safeToClean(), SETTLE_DEADLINE); // 보류면 예외로 끝나 아래 정리를 하지 않는다.
        if (ownedKeys != null) {
            ownedKeys.release();
        }
        databaseCleanUp.truncateAllTables();
    }

    private HttpEntity<Object> as(Long userId, String waitingId) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-USER-ID", String.valueOf(userId));
        }
        if (waitingId != null) {
            headers.set(CouponV1Controller.WAITING_ID_HEADER, waitingId);
        }
        return new HttpEntity<>(null, headers);
    }

    private <T> ResponseEntity<T> call(HttpMethod method, String path, Long userId, String waitingId,
                                       ParameterizedTypeReference<T> type) {
        return settlement.observe(method + " " + path + " user " + userId, () ->
            rest.exchange(path, method, as(userId, waitingId), type));
    }

    private ResponseEntity<String> raw(HttpMethod method, String path, Long userId, String waitingId) {
        return settlement.observe(method + " " + path + " user " + userId, () ->
            rest.exchange(path, method, as(userId, waitingId), String.class));
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.WaitingResponse>> register(Long userId, Object campaignId) {
        return call(HttpMethod.POST, "/api/v1/coupon-campaigns/" + campaignId + "/waiting", userId, null, WAITING);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.WaitingResponse>> waiting(Long userId, Object campaignId) {
        return call(HttpMethod.GET, "/api/v1/coupon-campaigns/" + campaignId + "/waiting", userId, null, WAITING);
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> issue(Long userId, Object campaignId, String waitingId) {
        return call(HttpMethod.POST, "/api/v1/coupon-campaigns/" + campaignId + "/issues", userId, waitingId, COUPON);
    }

    private ResponseEntity<ApiResponse<PageResponse<CouponV1Dto.OwnedCouponResponse>>> list(Long userId, String query) {
        return call(HttpMethod.GET, "/api/v1/coupons" + query, userId, null, PAGE);
    }

    /** 등록(201) 뒤 입장 처리 한 회차로 모두 READY 로 만든다. 사용자 → waitingId. */
    private Map<Long, String> admit(List<Long> users) {
        Map<Long, String> ids = new LinkedHashMap<>();
        for (Long user : users) {
            var registered = register(user, OPERATING);
            assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            ids.put(user, registered.getBody().data().waitingId());
        }
        assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(0, users.size()));
        return ids;
    }

    private String admit(Long user) {
        return admit(List.of(user)).get(user);
    }

    private CouponV1Dto.CouponResponse issued(Long user) {
        var response = issue(user, OPERATING, admit(user));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().data();
    }

    private long occupancy() {
        Long ready = master.opsForZSet().size("waiting:ready:v1:" + OPERATING);
        Long processing = master.opsForSet().size("waiting:processing:v1:" + OPERATING);
        return (ready == null ? 0 : ready) + (processing == null ? 0 : processing);
    }

    private void assertNoWaitingKeys(Long campaignId) {
        for (String key : CouponAdmissionRedisRepository.waitingKeys(campaignId)) {
            assertThat(master.type(key)).as("Redis 미조회·미변경: " + key).isEqualTo(DataType.NONE);
        }
    }

    private static String errorCode(ResponseEntity<? extends ApiResponse<?>> response) {
        return response.getBody().meta().errorCode();
    }

    private static JsonNode data(ResponseEntity<String> response) throws Exception {
        return JSON.readTree(response.getBody()).get("data");
    }

    @DisplayName("준비 정리 조건: 대기실 키가 하나라도 이미 있으면(첫 키·마지막 키 각각) 소유를 거절하고 정리에서 지우지 않는다. 모두 비었을 때만 이후 생긴 키를 지운다.")
    @Test
    void existingWaitingKeysAreNeverCleanedUp() {
        long probe = 9_901L; // 이 검사만 쓰는 별도 캠페인 범위. 비어 있지 않으면 건드리지 않고 실패한다.
        List<String> keys = CouponAdmissionRedisRepository.waitingKeys(probe);
        assertNoWaitingKeys(probe);
        for (String markerKey : List.of(keys.get(0), keys.get(keys.size() - 1))) {
            String marker = "coupon-owned-keys-marker-" + UUID.randomUUID();
            master.opsForValue().set(markerKey, marker);
            try {
                OwnedWaitingKeys keysUnderTest = new OwnedWaitingKeys(master, probe);
                assertThatThrownBy(keysUnderTest::claim).isInstanceOf(IllegalStateException.class).hasMessageContaining(markerKey);
                keysUnderTest.release();
                assertThat(master.opsForValue().get(markerKey)).as("기존 키 보존: " + markerKey).isEqualTo(marker);
            } finally {
                if (marker.equals(master.opsForValue().get(markerKey))) {
                    master.delete(markerKey); // 이 검사가 만든 marker 만 지운다.
                }
            }
        }

        OwnedWaitingKeys owned = new OwnedWaitingKeys(master, probe);
        owned.claim();
        master.opsForValue().set(keys.get(keys.size() - 1), "created-after-claim");
        owned.release();
        assertNoWaitingKeys(probe);
    }

    @DisplayName("생성된 OpenAPI 의 발급 응답은 200·201 이 meta + CouponResponse data, 202 가 meta + WaitingResponse data 다.")
    @Test
    void issueResponsesInGeneratedOpenApi() throws Exception {
        JsonNode docs = JSON.readTree(rest.getForObject("/v3/api-docs", String.class));
        JsonNode responses = docs.at("/paths/~1api~1v1~1coupon-campaigns~1{campaignId}~1issues/post/responses");
        Map<String, List<String>> dataFields = Map.of(
            "200", List.of("couponId", "campaignId", "campaignName", "benefitType", "benefitValue"),
            "201", List.of("couponId", "campaignId", "campaignName", "benefitType", "benefitValue"),
            "202", List.of("campaignId", "waitingId", "status", "position", "expiresAt", "couponId"));

        for (var expected : dataFields.entrySet()) {
            JsonNode envelope = resolve(docs, responses.at("/" + expected.getKey() + "/content/application~1json/schema"));
            assertAll(expected.getKey(),
                () -> assertThat(fieldNames(envelope.get("properties"))).containsExactlyInAnyOrder("meta", "data"),
                () -> assertThat(fieldNames(resolve(docs, envelope.at("/properties/meta")).get("properties")))
                    .containsExactlyInAnyOrder("result", "errorCode", "message"),
                () -> assertThat(fieldNames(resolve(docs, envelope.at("/properties/data")).get("properties")))
                    .containsExactlyInAnyOrderElementsOf(expected.getValue())
            );
        }
    }

    /** $ref 는 components 의 실제 대상으로, allOf 는 각 부분의 properties 를 합쳐 따라간다. */
    private static JsonNode resolve(JsonNode docs, JsonNode schema) {
        assertThat(schema.isMissingNode()).as("schema 없음").isFalse();
        if (schema.has("$ref")) {
            String name = schema.get("$ref").asText().replace("#/components/schemas/", "");
            JsonNode target = docs.path("components").path("schemas").path(name);
            assertThat(target.isMissingNode()).as("참조 대상 없음: " + name).isFalse();
            return resolve(docs, target);
        }
        if (schema.has("allOf")) {
            com.fasterxml.jackson.databind.node.ObjectNode merged = JSON.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode properties = merged.putObject("properties");
            schema.get("allOf").forEach(part -> {
                JsonNode resolved = resolve(docs, part);
                if (resolved.has("properties")) {
                    properties.setAll((com.fasterxml.jackson.databind.node.ObjectNode) resolved.get("properties"));
                }
            });
            return merged;
        }
        return schema;
    }

    private static List<String> fieldNames(JsonNode node) {
        assertThat(node).as("properties 없음").isNotNull();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Nested
    @DisplayName("POST·GET /api/v1/coupon-campaigns/{campaignId}/waiting")
    class Waiting {

        @DisplayName("신규 201 WAITING·순번, 재등록 200 같은 ID, 조회 200. 입장 뒤 READY 는 UTC 6자리 expiresAt 만 있고, 등록 이력 없는 조회는 404 다.")
        @Test
        void registerReuseAndQuery() throws Exception {
            fixture.operatingCampaign(OPERATING, 5);
            List<Long> users = fixture.users(3);

            ResponseEntity<String> created = raw(HttpMethod.POST, "/api/v1/coupon-campaigns/" + OPERATING + "/waiting", users.get(0), null);
            JsonNode first = data(created);
            var again = register(users.get(0), OPERATING);
            var second = register(users.get(1), OPERATING);
            assertAll(
                () -> assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(first.get("status").asText()).isEqualTo("WAITING"),
                () -> assertThat(first.get("position").asLong()).isEqualTo(1L),
                () -> assertThat(first.get("campaignId").asLong()).isEqualTo(OPERATING),
                () -> assertThat(first.has("expiresAt") || first.has("couponId")).as("null 필드 생략").isFalse(),
                () -> assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(again.getBody().data().waitingId()).isEqualTo(first.get("waitingId").asText()),
                () -> assertThat(second.getBody().data().position()).isEqualTo(2L),
                () -> assertThat(waiting(users.get(0), OPERATING).getBody().data().position()).isEqualTo(1L)
            );

            assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(0, 2));
            JsonNode ready = data(raw(HttpMethod.GET, "/api/v1/coupon-campaigns/" + OPERATING + "/waiting", users.get(0), null));
            var unknown = waiting(users.get(2), OPERATING);
            assertAll(
                () -> assertThat(ready.get("status").asText()).isEqualTo("READY"),
                () -> assertThat(ready.get("expiresAt").asText()).matches(UTC_MICROS),
                () -> assertThat(ready.has("position")).isFalse(),
                () -> assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(errorCode(unknown)).isEqualTo("COUPON_WAITING_NOT_FOUND")
            );
        }

        @DisplayName("기존 쿠폰이 있으면 등록·조회는 Redis 항목 없이 200 ISSUED·couponId 이고 waitingId 는 없다. 닫혀 있어도 같다.")
        @Test
        void storedCouponWins() throws Exception {
            fixture.operatingCampaign(OPERATING, 5);
            Long user = fixture.user();
            Long couponId = issued(user).couponId();
            couponFacade.close();

            ResponseEntity<String> registered = raw(HttpMethod.POST, "/api/v1/coupon-campaigns/" + OPERATING + "/waiting", user, null);
            JsonNode body = data(registered);
            var queried = waiting(user, OPERATING);
            assertAll(
                () -> assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(body.get("status").asText()).isEqualTo("ISSUED"),
                () -> assertThat(body.get("couponId").asLong()).isEqualTo(couponId),
                () -> assertThat(body.has("waitingId")).isFalse(),
                () -> assertThat(queried.getBody().data().couponId()).isEqualTo(couponId),
                () -> assertThat(issue(user, OPERATING, null).getStatusCode()).as("헤더 없이 기존 결과").isEqualTo(HttpStatus.OK)
            );
        }

        @DisplayName("운영 캠페인이 아니면 등록·조회·발급 모두 503 이고 그 캠페인의 Redis 키를 만들지 않는다. 닫힘은 등록만 막고 조회는 허용한다.")
        @Test
        void otherCampaignAndClosed() {
            fixture.operatingCampaign(OPERATING, 5);
            Long other = fixture.activeCampaign(5);
            Long user = fixture.user();
            var registered = register(user, OPERATING);

            assertAll(
                () -> assertThat(errorCode(register(user, other))).isEqualTo("COUPON_ADMISSION_UNAVAILABLE"),
                () -> assertThat(waiting(user, other).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
                () -> assertThat(issue(user, other, registered.getBody().data().waitingId()).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
                () -> assertNoWaitingKeys(other)
            );
            couponFacade.close();
            assertAll(
                () -> assertThat(register(fixture.user(), OPERATING).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
                () -> assertThat(waiting(user, OPERATING).getStatusCode()).as("닫힘만으로 조회를 막지 않는다").isEqualTo(HttpStatus.OK),
                () -> assertThat(holding.starts()).isEmpty()
            );
        }
    }

    @Nested
    @DisplayName("POST /api/v1/coupon-campaigns/{campaignId}/issues")
    class Issue {

        @DisplayName("Q1·Q2: 최초 201 과 재요청 200(헤더 없이도) 이 같은 다섯 필드·같은 couponId 이고, 쿠폰 1·발급 수 1·자리 반환이며 재요청은 start 하지 않는다.")
        @Test
        void createdThenStored() {
            fixture.operatingCampaign(OPERATING, "가을 감사", 10, CouponBenefitType.FIXED_AMOUNT, 5_000L);
            Long user = fixture.user();
            String waitingId = admit(user);

            var first = issue(user, OPERATING, waitingId);
            int startsAfterFirst = holding.starts().size();
            var second = issue(user, OPERATING, null);
            var withHeader = issue(user, OPERATING, waitingId);

            CouponV1Dto.CouponResponse created = first.getBody().data();
            assertAll(
                () -> assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(created.couponId()).isNotNull(),
                () -> assertThat(created.campaignId()).isEqualTo(OPERATING),
                () -> assertThat(created.campaignName()).isEqualTo("가을 감사"),
                () -> assertThat(created.benefitType()).isEqualTo(CouponBenefitType.FIXED_AMOUNT),
                () -> assertThat(created.benefitValue()).isEqualTo(5_000L),
                () -> assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK),
                () -> assertThat(second.getBody().data()).isEqualTo(created),
                () -> assertThat(withHeader.getBody().data()).isEqualTo(created),
                () -> assertThat(fixture.couponCount(OPERATING)).isEqualTo(1),
                () -> assertThat(fixture.issuedCount(OPERATING)).isEqualTo(1),
                () -> assertThat(holding.starts()).as("재요청은 입장 권한을 쓰지 않는다").hasSize(startsAfterFirst),
                () -> assertThat(holding.finishes()).containsExactly(new Finish(user, waitingId, CouponWaitingState.ISSUED)),
                () -> assertThat(occupancy()).isZero()
            );
        }

        @DisplayName("Q2: 비활성·품절·수용 닫힘 뒤에도 기존 쿠폰은 200·같은 ID 이고, 이름은 현재 캠페인 값·혜택은 저장된 과거 값이다.")
        @Test
        void storedWinsOverInactiveSoldOutClosed() {
            fixture.operatingCampaign(OPERATING, "처음 이름", 3, CouponBenefitType.FIXED_AMOUNT, 5_000L);
            Long user = fixture.user();
            Long couponId = issued(user).couponId();
            fixture.rename(OPERATING, "바뀐 이름");
            fixture.changeBenefit(OPERATING, CouponBenefitType.PERCENTAGE, 10L);

            jdbcTemplate.update("UPDATE coupon_campaign SET status = 'INACTIVE' WHERE id = ?", OPERATING);
            var inactive = issue(user, OPERATING, null);
            jdbcTemplate.update("UPDATE coupon_campaign SET status = 'ACTIVE', issued_count = total_quota WHERE id = ?", OPERATING);
            var soldOut = issue(user, OPERATING, null);
            couponFacade.close();
            var closed = issue(user, OPERATING, null);

            assertAll(
                () -> assertThat(List.of(inactive, soldOut, closed)).allSatisfy(r -> {
                    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
                    assertThat(r.getBody().data().couponId()).isEqualTo(couponId);
                    assertThat(r.getBody().data().campaignName()).isEqualTo("바뀐 이름");
                    assertThat(r.getBody().data().benefitType()).isEqualTo(CouponBenefitType.FIXED_AMOUNT);
                    assertThat(r.getBody().data().benefitValue()).isEqualTo(5_000L);
                }),
                () -> assertThat(fixture.couponCount(OPERATING)).isEqualTo(1),
                () -> assertThat(holding.finishes()).as("최초 발급 한 번만 자리를 썼다").hasSize(1)
            );
        }

        @DisplayName("업무 거절: 운영 캠페인이 DB 에 없으면 등록 404, 비활성·품절이면 등록 409. READY 뒤 비활성·품절이 되면 발급 409·FAILED 이고 같은 권한은 ALREADY_USED, 쿠폰·발급 수 효과는 없다.")
        @Test
        void businessRejections() {
            Long user = fixture.user();
            var missing = register(user, OPERATING);
            assertAll(
                () -> assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(errorCode(missing)).isEqualTo("COUPON_CAMPAIGN_NOT_FOUND"),
                () -> assertNoWaitingKeys(OPERATING)
            );

            fixture.operatingCampaign(OPERATING, 5);
            jdbcTemplate.update("UPDATE coupon_campaign SET status = 'INACTIVE' WHERE id = ?", OPERATING);
            var inactiveRegister = register(user, OPERATING);
            jdbcTemplate.update("UPDATE coupon_campaign SET status = 'ACTIVE', issued_count = total_quota WHERE id = ?", OPERATING);
            var soldOutRegister = register(user, OPERATING);
            jdbcTemplate.update("UPDATE coupon_campaign SET issued_count = 0 WHERE id = ?", OPERATING);
            assertAll(
                () -> assertThat(inactiveRegister.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(errorCode(inactiveRegister)).isEqualTo("COUPON_CAMPAIGN_INACTIVE"),
                () -> assertThat(errorCode(soldOutRegister)).isEqualTo("COUPON_CAMPAIGN_SOLD_OUT"),
                () -> assertNoWaitingKeys(OPERATING)
            );

            List<Long> users = fixture.users(2);
            Map<Long, String> ids = admit(users);
            jdbcTemplate.update("UPDATE coupon_campaign SET status = 'INACTIVE' WHERE id = ?", OPERATING);
            var inactiveIssue = issue(users.get(0), OPERATING, ids.get(users.get(0)));
            jdbcTemplate.update("UPDATE coupon_campaign SET status = 'ACTIVE', issued_count = total_quota WHERE id = ?", OPERATING);
            var soldOutIssue = issue(users.get(1), OPERATING, ids.get(users.get(1)));
            var reused = issue(users.get(0), OPERATING, ids.get(users.get(0)));

            assertAll(
                () -> assertThat(inactiveIssue.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(errorCode(inactiveIssue)).isEqualTo("COUPON_CAMPAIGN_INACTIVE"),
                () -> assertThat(errorCode(soldOutIssue)).isEqualTo("COUPON_CAMPAIGN_SOLD_OUT"),
                () -> assertThat(errorCode(reused)).isEqualTo("COUPON_WAITING_ALREADY_USED"),
                () -> assertThat(holding.finishes()).extracting(Finish::outcome).containsOnly(CouponWaitingState.FAILED).hasSize(2),
                () -> assertThat(fixture.couponCount(OPERATING)).isZero(),
                () -> assertThat(fixture.issuedCount(OPERATING)).as("품절로 바꾼 값 그대로").isEqualTo(5),
                () -> assertThat(occupancy()).isZero(),
                () -> assertThat(couponFacade.status()).isTrue()
            );
        }

        @DisplayName("권한 오류: 헤더 없음 400 ID_REQUIRED, 모르는 ID·다른 사용자 ID 404, 아직 WAITING 409 NOT_READY 이고 DB 효과가 없다.")
        @Test
        void waitingIdErrors() {
            fixture.operatingCampaign(OPERATING, 5);
            List<Long> users = fixture.users(2);
            String readyId = admit(users.get(0));
            String waitingId = register(users.get(1), OPERATING).getBody().data().waitingId();

            var noHeader = issue(users.get(0), OPERATING, null);
            var unknown = issue(users.get(0), OPERATING, "unknown-id");
            var othersId = issue(users.get(0), OPERATING, waitingId);
            var notReady = issue(users.get(1), OPERATING, waitingId);
            assertAll(
                () -> assertThat(noHeader.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(errorCode(noHeader)).isEqualTo("COUPON_WAITING_ID_REQUIRED"),
                () -> assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(errorCode(unknown)).isEqualTo("COUPON_WAITING_NOT_FOUND"),
                () -> assertThat(errorCode(othersId)).isEqualTo("COUPON_WAITING_NOT_FOUND"),
                () -> assertThat(notReady.getStatusCode()).isEqualTo(HttpStatus.CONFLICT),
                () -> assertThat(errorCode(notReady)).isEqualTo("COUPON_WAITING_NOT_READY"),
                () -> assertThat(fixture.issuedCount(OPERATING)).isZero(),
                () -> assertThat(holding.finishes()).isEmpty(),
                () -> assertThat(issue(users.get(0), OPERATING, readyId).getStatusCode()).isEqualTo(HttpStatus.CREATED)
            );
        }

        @DisplayName("요청자 식별 오류는 기존 계약 그대로다: 헤더 없음 400 INVALID_REQUEST, 없는 사용자 404 USER_NOT_FOUND. 등록·발급 모두 Redis 를 쓰지 않는다.")
        @Test
        void identification() {
            fixture.operatingCampaign(OPERATING, 5);

            var noHeader = issue(null, OPERATING, "any");
            var unknown = issue(999_999L, OPERATING, "any");
            var registerUnknown = register(999_999L, OPERATING);

            assertAll(
                () -> assertThat(noHeader.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST),
                () -> assertThat(errorCode(noHeader)).isEqualTo("INVALID_REQUEST"),
                () -> assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(errorCode(unknown)).isEqualTo("USER_NOT_FOUND"),
                () -> assertThat(errorCode(registerUnknown)).isEqualTo("USER_NOT_FOUND"),
                () -> assertThat(holding.starts()).isEmpty(),
                () -> assertNoWaitingKeys(OPERATING)
            );
        }

        @DisplayName("수용 거절이 캠페인 판단보다 먼저다: 닫힘이면 없는 캠페인 ID 도 503 이고 Redis 를 쓰지 않는다.")
        @Test
        void admissionBeforeCampaign() {
            Long user = fixture.user();
            couponFacade.close();

            var closedIssue = issue(user, 999_999L, "any");
            var closedRegister = register(user, OPERATING);

            assertAll(
                () -> assertThat(closedIssue.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
                () -> assertThat(errorCode(closedIssue)).isEqualTo("COUPON_ADMISSION_UNAVAILABLE"),
                () -> assertThat(errorCode(closedRegister)).isEqualTo("COUPON_ADMISSION_UNAVAILABLE"),
                () -> assertThat(holding.starts()).isEmpty(),
                () -> assertNoWaitingKeys(OPERATING)
            );
        }

        @DisplayName("WQ5: 같은 권한이 진행 중이면 두 번째 발급은 202·PROCESSING 대기 응답이고 추가 DB 효과가 없다. 첫 실행은 201 로 끝난다.")
        @Test
        void sameWaitingInProgressIsAccepted() throws Exception {
            fixture.operatingCampaign(OPERATING, 5);
            Long user = fixture.user();
            String waitingId = admit(user);
            holding.holdNextStarted(1, DEADLINE);
            ExecutorService executor = workers.start(1);
            Future<ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>>> first;
            ResponseEntity<String> duplicate;
            long issuedWhileHeld;
            try {
                first = executor.submit(() -> issue(user, OPERATING, waitingId));
                assertThat(holding.awaitHeld(DEADLINE)).isTrue();
                duplicate = raw(HttpMethod.POST, "/api/v1/coupon-campaigns/" + OPERATING + "/issues", user, waitingId);
                issuedWhileHeld = fixture.issuedCount(OPERATING);
                holding.letThrough(1);
            } finally {
                holding.stopHolding();
                workers.close(executor, TERMINATION_DEADLINE);
            }
            JsonNode body = data(duplicate);
            long heldIssued = issuedWhileHeld;
            assertAll(
                () -> assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED),
                () -> assertThat(body.get("status").asText()).isEqualTo("PROCESSING"),
                () -> assertThat(body.get("waitingId").asText()).isEqualTo(waitingId),
                () -> assertThat(body.has("position") || body.has("expiresAt") || body.has("couponId")).isFalse(),
                () -> assertThat(heldIssued).as("202 는 DB 쓰기 없음").isZero(),
                () -> assertThat(first.get(0, TimeUnit.MILLISECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED),
                () -> assertThat(fixture.issuedCount(OPERATING)).isEqualTo(1),
                () -> assertThat(holding.finishes()).hasSize(1)
            );
        }
    }

    @DisplayName("WQ3: 실제 Redis C=3 에서 세 실행을 STARTED 직후 붙잡으면 넷째는 READY 가 되지 못해 409 NOT_READY·DB 미진입이고, 하나가 끝난 뒤 다음 입장 처리로 승격돼 201 이며 최종 쿠폰 4·점유 0 이다.")
    @Test
    void capacityThreeHoldsThenReadmits() throws Exception {
        fixture.operatingCampaign(OPERATING, 10);
        List<Long> users = fixture.users(4);
        Map<Long, String> ids = new LinkedHashMap<>();
        for (Long user : users) {
            ids.put(user, register(user, OPERATING).getBody().data().waitingId());
        }
        assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(0, 3));
        Long fourth = users.get(3);
        holding.holdNextStarted(3, DEADLINE);
        ExecutorService executor = workers.start(3);
        List<Future<ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>>>> held = new ArrayList<>();
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> fourthRejected;
        ResponseEntity<ApiResponse<CouponV1Dto.CouponResponse>> fourthAdmitted;
        Optional<AdmissionProgress> whileFull;
        try {
            for (Long user : users.subList(0, 3)) {
                held.add(executor.submit(() -> issue(user, OPERATING, ids.get(user))));
            }
            assertThat(holding.awaitHeld(DEADLINE)).as("세 요청이 실제 STARTED 뒤 붙잡혀야 한다").isTrue();
            whileFull = couponFacade.processAdmissions();
            fourthRejected = issue(fourth, OPERATING, ids.get(fourth));
            assertAll(
                () -> assertThat(whileFull).contains(new AdmissionProgress(0, 0)),
                () -> assertThat(occupancy()).isEqualTo(3),
                () -> assertThat(errorCode(fourthRejected)).isEqualTo("COUPON_WAITING_NOT_READY"),
                () -> assertThat(fixture.issuedCount(OPERATING)).as("DB 발급 경로 미진입").isZero()
            );

            holding.letThrough(1);
            assertThat(holding.awaitFinished(1, DEADLINE)).as("한 실행의 서버 측 종료 기록 관찰").isTrue();
            assertThat(couponFacade.processAdmissions()).contains(new AdmissionProgress(0, 1));
            fourthAdmitted = issue(fourth, OPERATING, ids.get(fourth));
            holding.letThrough(2);
        } finally {
            holding.stopHolding();
            workers.close(executor, TERMINATION_DEADLINE);
        }
        List<HttpStatus> heldStatuses = new ArrayList<>();
        for (var future : held) {
            heldStatuses.add(HttpStatus.valueOf(future.get(0, TimeUnit.MILLISECONDS).getStatusCode().value()));
        }
        assertThat(holding.awaitFinished(4, DEADLINE)).isTrue();

        System.out.printf("[coupon-wq3-http] campaign=%d C=3 fourthFirst=%s fourthRetry=%s heldStatuses=%s issued=%d coupons=%d finishes=%d occupancy=%d%n",
            OPERATING, fourthRejected.getStatusCode(), fourthAdmitted.getStatusCode(), heldStatuses,
            fixture.issuedCount(OPERATING), fixture.couponCount(OPERATING), holding.finishes().size(), occupancy());
        assertAll(
            () -> assertThat(fourthAdmitted.getStatusCode()).isEqualTo(HttpStatus.CREATED),
            () -> assertThat(heldStatuses).containsOnly(HttpStatus.CREATED),
            () -> assertThat(fixture.couponCount(OPERATING)).isEqualTo(4),
            () -> assertThat(fixture.issuedCount(OPERATING)).isEqualTo(4),
            () -> assertThat(holding.finishes()).hasSize(4),
            () -> assertThat(occupancy()).isZero(),
            () -> assertThat(couponFacade.status()).isTrue()
        );
    }

    @Nested
    @DisplayName("GET /api/v1/coupons")
    class MyCoupons {

        @DisplayName("Q1·Q15: 본인 쿠폰만 저장 시각·ID 로 안정 정렬해 주고, createdAt 은 DB 저장 시각의 UTC·소수 6자리·Z 다. 운영 캠페인은 API 로, 과거 캠페인은 DB 로 준비한다.")
        @Test
        void ownCouponsOnly() {
            Long me = fixture.user();
            Long other = fixture.user();
            Long a = fixture.operatingCampaign(OPERATING, "A", 5, CouponBenefitType.FIXED_AMOUNT, 1_000L);
            Long b = fixture.activeCampaign("B", 5, CouponBenefitType.PERCENTAGE, 15L);
            Long c = fixture.activeCampaign("C", 5, CouponBenefitType.FIXED_AMOUNT, 3_000L);
            Long ca = issued(me).couponId();
            Long cb = fixture.storedCoupon(me, b);
            Long cc = fixture.storedCoupon(me, c);
            Long othersCoupon = fixture.storedCoupon(other, a);
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

        private boolean status() {
            return settlement.observe("GET admission", () ->
                    rest.exchange("/api/coupon/admission", HttpMethod.GET, HttpEntity.EMPTY, ADMISSION))
                .getBody().data().accepting();
        }

        @DisplayName("close·open 은 반복 가능하고 현재 값을 돌려주며, GET 은 같은 공유 값을 보여준다. 닫힌 뒤 신규 등록·발급은 503·Redis 미호출이다.")
        @Test
        void closeAndOpen() {
            fixture.operatingCampaign(OPERATING, 5);
            Long user = fixture.user();
            String waitingId = admit(user);

            assertAll(
                () -> assertThat(post("close").getBody().data().accepting()).isFalse(),
                () -> assertThat(post("close").getBody().data().accepting()).isFalse(),
                () -> assertThat(status()).isFalse(),
                () -> assertThat(couponFacade.status()).isFalse()
            );
            var rejected = issue(user, OPERATING, waitingId);
            assertAll(
                () -> assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE),
                () -> assertThat(holding.starts()).isEmpty(),
                () -> assertThat(fixture.issuedCount(OPERATING)).isZero()
            );
            assertAll(
                () -> assertThat(post("open").getBody().data().accepting()).isTrue(),
                () -> assertThat(status()).isTrue(),
                () -> assertThat(issue(user, OPERATING, waitingId).getStatusCode()).isEqualTo(HttpStatus.CREATED)
            );
        }

        @DisplayName("기본 token 정리 API(cleanup)는 제거돼 404 이고 수용 상태·대기실 키를 바꾸지 않는다.")
        @Test
        void cleanupRemoved() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            var response = settlement.observe("POST admission cleanup", () -> rest.exchange("/api/coupon/admission/cleanup",
                HttpMethod.POST, new HttpEntity<>("{\"tokens\":[\"a\"]}", headers), ADMISSION));

            assertAll(
                () -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND),
                () -> assertThat(status()).isTrue(),
                () -> assertNoWaitingKeys(OPERATING)
            );
        }
    }
}
