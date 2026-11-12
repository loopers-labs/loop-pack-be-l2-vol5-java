package com.loopers.infrastructure.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.coupon.CouponBenefitType;
import com.loopers.domain.coupon.CouponCacheRepository.IssuedCoupon;
import com.loopers.fixture.CouponRedisTarget;
import com.loopers.testcontainers.RedisTestContainersConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 캐시 Redis adapter 와 읽기 Lua 를 fixture Redis 컨테이너에서 실제로 실행한다(설계 §13.9, KQ2 와 KQ6·KQ8 의 저장소 부분).
 * Facade·DB·HTTP·로컬 품절 슬롯은 다루지 않는다. 실제 Redis 장애·timeout 재현도 아니다.
 *
 * <p>대상 확인은 다른 쿠폰 Redis 검사와 같다(CouponRedisTarget 으로 주소 고정·run_id 대조, 실패 시 Redis 명령 없음).
 * 사례마다 서로 다른 범위의 임의 campaignId·userId 를 골라, 쓸 캐시 키가 모두 비어 있음을 확인한 뒤에만 소유한다.
 * 남은 키는 지우지 않고 실패하며, worker 종료를 확인한 경우에만 자기 키를 지운다. FLUSHALL·컨테이너 조작은 하지 않는다.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("캐시 adapter 는 campaign:user 키에 다섯 필드 JSON·true 를 NX+TTL 로 쓰고, GET+PTTL 을 원자적으로 읽어 TTL 없는 키·손상 값을 hit 으로 쓰지 않는다.")
class CouponCacheRedisIntegrationTest {

    private static final long TTL = 60_000L;
    private static final Duration TERMINATION_DEADLINE = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private CouponCacheRedisRepository repository;
    @Autowired
    private RedisProperties redisProperties;
    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> master;

    private final WorkerTermination workers = new WorkerTermination();
    private final Set<String> ownKeys = new LinkedHashSet<>();
    private boolean targetVerified;
    private long campaignId;
    private long userId;
    private IssuedCoupon value;

    @DynamicPropertySource
    static void pinToFixtureContainer(DynamicPropertyRegistry registry) {
        CouponRedisTarget.register(registry);
    }

    @BeforeEach
    void requireFixtureContainerAndEmptyKeys() {
        CouponRedisTarget.verify(redisProperties, master);
        targetVerified = true;
        // 두 ID 의 범위를 나눠 키 안의 순서가 바뀌면 다른 키가 되게 한다.
        campaignId = ThreadLocalRandom.current().nextLong(1_000_000_000_000L, 2_000_000_000_000L);
        userId = ThreadLocalRandom.current().nextLong(3_000_000_000_000L, 4_000_000_000_000L);
        claimEmpty(CouponCacheRedisRepository.issuedKey(userId, campaignId));
        claimEmpty(CouponCacheRedisRepository.issuedKey(campaignId, userId)); // 인자 순서가 뒤바뀐 경우의 키
        claimEmpty(CouponCacheRedisRepository.soldOutKey(campaignId));
        value = new IssuedCoupon(5_000_000_000_000L + campaignId, campaignId, "가을 캠페인", CouponBenefitType.FIXED_AMOUNT, 5_000L);
    }

    @AfterEach
    void deleteOwnKeys() {
        if (!targetVerified) {
            return; // 대상 확인 전이면 어떤 Redis 에도 명령을 보내지 않는다.
        }
        if (!workers.safeToClean()) {
            throw new IllegalStateException("worker 종료를 확인하지 못해 캐시 키 정리를 보류한다. [keys = " + ownKeys + "]");
        }
        master.delete(ownKeys);
    }

    /** 키가 비어 있음을 확인하고 소유로 기록한다. 남은 값은 다른 실행의 것일 수 있어 지우지 않는다. */
    private void claimEmpty(String key) {
        assertThat(master.type(key)).as("시작 전 키가 비어 있어야 한다: " + key).isEqualTo(DataType.NONE);
        ownKeys.add(key);
    }

    private String issuedKey() {
        return CouponCacheRedisRepository.issuedKey(userId, campaignId);
    }

    private String soldOutKey() {
        return CouponCacheRedisRepository.soldOutKey(campaignId);
    }

    private long pttl(String key) {
        return master.getExpire(key, TimeUnit.MILLISECONDS);
    }

    @DisplayName("주입된 template 은 fixture 컨테이너의 master 서버에 연결된다.")
    @Test
    void usesFixtureMaster() {
        Properties replication = master.execute((RedisCallback<Properties>) c -> c.serverCommands().info("replication"));
        Properties server = master.execute((RedisCallback<Properties>) c -> c.serverCommands().info("server"));

        System.out.printf("[coupon-cache] master=%s:%d database=%d container=%s containerRunId=%s connectedRunId=%s redis_version=%s role=%s%n",
            redisProperties.master().host(), redisProperties.master().port(), redisProperties.database(),
            RedisTestContainersConfig.containerId(), RedisTestContainersConfig.serverRunId(), server.getProperty("run_id"),
            server.getProperty("redis_version"), replication.getProperty("role"));
        assertAll(
            () -> assertThat(server.getProperty("run_id")).isEqualTo(RedisTestContainersConfig.serverRunId()),
            () -> assertThat(replication.getProperty("role")).isEqualTo("master")
        );
    }

    @DisplayName("기발급 키는 campaign:user 순서이고 값은 정수·문자열 타입의 다섯 필드 JSON 이며, 포트 인자를 뒤바꾸면 다른 키라 miss 다.")
    @Test
    void issuedKeyOrderAndJson() throws Exception {
        repository.putIssuedIfAbsent(userId, campaignId, value, TTL);

        String raw = master.opsForValue().get("coupon:issued:v1:" + campaignId + ":" + userId);
        JsonNode node = JSON.readTree(raw);
        List<String> fields = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            fields.add(it.next());
        }
        assertAll(
            () -> assertThat(issuedKey()).isEqualTo("coupon:issued:v1:" + campaignId + ":" + userId),
            () -> assertThat(fields).containsExactlyInAnyOrder("couponId", "campaignId", "campaignName", "benefitType", "benefitValue"),
            () -> assertThat(node.get("couponId").isIntegralNumber()).isTrue(),
            () -> assertThat(node.get("couponId").longValue()).isEqualTo(value.couponId()),
            () -> assertThat(node.get("campaignId").longValue()).isEqualTo(campaignId),
            () -> assertThat(node.get("campaignName").textValue()).isEqualTo("가을 캠페인"),
            () -> assertThat(node.get("benefitType").textValue()).isEqualTo("FIXED_AMOUNT"),
            () -> assertThat(node.get("benefitValue").isIntegralNumber()).isTrue(),
            () -> assertThat(pttl(issuedKey())).isBetween(1L, TTL),
            () -> assertThat(repository.findIssued(userId, campaignId)).contains(value),
            () -> assertThat(repository.findIssued(campaignId, userId)).isEmpty(),
            () -> assertThat(master.type(CouponCacheRedisRepository.issuedKey(campaignId, userId))).isEqualTo(DataType.NONE)
        );
    }

    @DisplayName("품절 키는 캠페인별이고 값은 정확히 true 이며, 조회는 0 보다 크고 TTL 이하인 남은 밀리초를 준다.")
    @Test
    void soldOutKeyAndRemaining() {
        repository.putSoldOutIfAbsent(campaignId, TTL);

        OptionalLong remaining = repository.findSoldOutTtl(campaignId);
        assertAll(
            () -> assertThat(soldOutKey()).isEqualTo("coupon:sold-out:v1:" + campaignId),
            () -> assertThat(master.opsForValue().get(soldOutKey())).isEqualTo("true"),
            () -> assertThat(remaining).isPresent(),
            () -> assertThat(remaining.getAsLong()).isBetween(1L, TTL),
            () -> assertThat(pttl(soldOutKey())).isBetween(1L, TTL)
        );
    }

    @DisplayName("이미 있는 키에는 NX 로 다른 값·더 긴 TTL 을 덮어쓰지 않고, 반복 조회도 TTL 을 늘리지 않는다.")
    @Test
    void nxKeepsValueAndReadsDoNotExtend() throws Exception {
        repository.putIssuedIfAbsent(userId, campaignId, value, TTL);
        repository.putSoldOutIfAbsent(campaignId, TTL);
        String first = master.opsForValue().get(issuedKey());
        Thread.sleep(50);

        IssuedCoupon other = new IssuedCoupon(value.couponId() + 1, campaignId, "다른 이름", CouponBenefitType.PERCENTAGE, 10L);
        repository.putIssuedIfAbsent(userId, campaignId, other, TTL * 10);
        repository.putSoldOutIfAbsent(campaignId, TTL * 10);

        long issuedBefore = pttl(issuedKey());
        long soldOutBefore = pttl(soldOutKey());
        for (int i = 0; i < 5; i++) {
            assertThat(repository.findIssued(userId, campaignId)).contains(value);
            assertThat(repository.findSoldOutTtl(campaignId)).isPresent();
        }
        assertAll(
            () -> assertThat(master.opsForValue().get(issuedKey())).isEqualTo(first),
            () -> assertThat(issuedBefore).isLessThan(TTL),
            () -> assertThat(soldOutBefore).isLessThan(TTL),
            () -> assertThat(pttl(issuedKey())).isLessThanOrEqualTo(issuedBefore),
            () -> assertThat(pttl(soldOutKey())).isLessThanOrEqualTo(soldOutBefore),
            () -> assertThat(repository.findSoldOutTtl(campaignId).getAsLong()).isLessThanOrEqualTo(soldOutBefore)
        );
    }

    @DisplayName("TTL 이 지나면 miss·키 없음이고, 그 뒤 새 값으로 다시 채울 수 있다.")
    @Test
    void expiresThenRefills() throws Exception {
        repository.putIssuedIfAbsent(userId, campaignId, value, 200);
        repository.putSoldOutIfAbsent(campaignId, 200);
        assertThat(repository.findIssued(userId, campaignId)).contains(value);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((repository.findIssued(userId, campaignId).isPresent() || repository.findSoldOutTtl(campaignId).isPresent())
            && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertAll(
            () -> assertThat(repository.findIssued(userId, campaignId)).isEmpty(),
            () -> assertThat(repository.findSoldOutTtl(campaignId)).isEmpty(),
            () -> assertThat(master.type(issuedKey())).isEqualTo(DataType.NONE)
        );

        IssuedCoupon renamed = new IssuedCoupon(value.couponId(), campaignId, "바뀐 이름", value.benefitType(), value.benefitValue());
        repository.putIssuedIfAbsent(userId, campaignId, renamed, TTL);
        repository.putSoldOutIfAbsent(campaignId, TTL);
        assertAll(
            () -> assertThat(repository.findIssued(userId, campaignId)).contains(renamed),
            () -> assertThat(repository.findSoldOutTtl(campaignId)).isPresent()
        );
    }

    @DisplayName("동시 최초 채움 8건 중 하나만 남고, 남은 값은 시도한 값 중 하나이며 TTL 이 있다.")
    @Test
    void concurrentFirstFillKeepsOne() throws Exception {
        int n = 8;
        List<IssuedCoupon> candidates = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            candidates.add(new IssuedCoupon(value.couponId() + i, campaignId, "후보 " + i, CouponBenefitType.FIXED_AMOUNT, 1_000L + i));
        }
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = workers.start(n);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (IssuedCoupon candidate : candidates) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    repository.putIssuedIfAbsent(userId, campaignId, candidate, TTL);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            go.countDown();
            workers.close(executor, TERMINATION_DEADLINE);
        }

        Optional<IssuedCoupon> stored = repository.findIssued(userId, campaignId);
        assertAll(
            () -> assertThat(stored).isPresent(),
            () -> assertThat(candidates).contains(stored.orElseThrow()),
            () -> assertThat(pttl(issuedKey())).isBetween(1L, TTL)
        );
    }

    @DisplayName("TTL 없는 키는 값이 정상이어도 miss 이고, 키를 지우거나 TTL 을 붙이지 않는다.")
    @Test
    void noTtlKeyIsMissAndUntouched() {
        master.opsForValue().set(issuedKey(), CouponCacheRedisRepository.toJson(value));
        master.opsForValue().set(soldOutKey(), "true");

        assertAll(
            () -> assertThat(repository.findIssued(userId, campaignId)).isEmpty(),
            () -> assertThat(repository.findSoldOutTtl(campaignId)).isEmpty(),
            () -> assertThat(pttl(issuedKey())).isEqualTo(-1L),
            () -> assertThat(pttl(soldOutKey())).isEqualTo(-1L),
            () -> assertThat(master.opsForValue().get(issuedKey())).isEqualTo(CouponCacheRedisRepository.toJson(value))
        );
    }

    @DisplayName("손상된 기발급 JSON 은 hit 이 아니라 예외이며 키는 그대로 남는다(숫자 문자열·실수·범위 밖·누락·추가·null·불일치·중복 필드·뒤따르는 토큰 등).")
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"couponId\":\"9\",\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}",
        "{\"couponId\":9.0,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}",
        "{\"couponId\":9,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5e3}",
        "{\"couponId\":9223372036854775808,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}",
        "{\"couponId\":9,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\"}",
        "{\"couponId\":9,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000,\"created\":false}",
        "{\"couponId\":null,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}",
        "{\"couponId\":9,\"campaignId\":1,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}",
        "{\"couponId\":9,\"campaignId\":CAMPAIGN,\"campaignName\":7,\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}",
        "{\"couponId\":9,\"campaignId\":CAMPAIGN,\"campaignName\":\" 가을 \",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}",
        "{\"couponId\":9,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"UNKNOWN\",\"benefitValue\":5000}",
        "{\"couponId\":9,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"PERCENTAGE\",\"benefitValue\":101}",
        "{\"couponId\":0,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}",
        "{\"couponId\":9,\"couponId\":10,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000}",
        "{\"couponId\":9,\"campaignId\":CAMPAIGN,\"campaignName\":\"가을\",\"benefitType\":\"FIXED_AMOUNT\",\"benefitValue\":5000} {}",
        "null", "[]", "\"text\"", "not json", ""
    })
    void corruptIssuedJsonIsError(String template) {
        String json = template.replace("CAMPAIGN", String.valueOf(campaignId));
        master.opsForValue().set(issuedKey(), json, Duration.ofMillis(TTL));

        assertThatThrownBy(() -> repository.findIssued(userId, campaignId)).isInstanceOf(RuntimeException.class);
        assertAll(
            () -> assertThat(master.opsForValue().get(issuedKey())).isEqualTo(json),
            () -> assertThat(pttl(issuedKey())).isBetween(1L, TTL)
        );
    }

    @DisplayName("품절 값이 정확히 true 가 아니면 예외이며 키는 그대로 남는다.")
    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "1", "false", "true ", ""})
    void corruptSoldOutIsError(String raw) {
        master.opsForValue().set(soldOutKey(), raw, Duration.ofMillis(TTL));

        assertThatThrownBy(() -> repository.findSoldOutTtl(campaignId)).isInstanceOf(RuntimeException.class);
        assertThat(master.opsForValue().get(soldOutKey())).isEqualTo(raw);
    }

    @DisplayName("문자열이 아닌 타입의 키는 읽기 Lua 가 오류로 올리고 키를 바꾸지 않는다.")
    @Test
    void wrongTypeIsError() {
        master.opsForHash().put(issuedKey(), "f", "v");
        master.opsForSet().add(soldOutKey(), "true");

        assertThatThrownBy(() -> repository.findIssued(userId, campaignId)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> repository.findSoldOutTtl(campaignId)).isInstanceOf(RuntimeException.class);
        assertAll(
            () -> assertThat(master.type(issuedKey())).isEqualTo(DataType.HASH),
            () -> assertThat(master.type(soldOutKey())).isEqualTo(DataType.SET)
        );
    }

    @DisplayName("ID·TTL·값의 campaignId 불일치는 Redis 명령 전에 거절한다.")
    @Test
    void rejectsInvalidArguments() {
        IssuedCoupon otherCampaign = new IssuedCoupon(value.couponId(), campaignId + 1, "가을 캠페인", CouponBenefitType.FIXED_AMOUNT, 5_000L);
        assertAll(
            () -> assertThatThrownBy(() -> repository.findIssued(null, campaignId)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.findIssued(userId, 0L)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.findSoldOutTtl(-1L)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.putIssuedIfAbsent(userId, campaignId, value, 0)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.putIssuedIfAbsent(userId, campaignId, otherCampaign, TTL))
                .isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> repository.putSoldOutIfAbsent(campaignId, 0)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThat(master.type(issuedKey())).isEqualTo(DataType.NONE),
            () -> assertThat(master.type(soldOutKey())).isEqualTo(DataType.NONE)
        );
    }

    @DisplayName("읽기 Lua 응답은 [MISS,'',''] 또는 [HIT, 값, 양의 십진 정수]만 받고 나머지는 오류다.")
    @Test
    void readReplyConversion() {
        assertAll(
            () -> assertThat(CouponCacheRedisRepository.readHit(List.of("MISS", "", ""))).isEmpty(),
            () -> assertThat(CouponCacheRedisRepository.readHit(List.of("HIT", "true", "29999")))
                .contains(new CouponCacheRedisRepository.Hit("true", 29_999L))
        );
        List<Object> invalid = new ArrayList<>(List.of(
            "MISS", List.of("MISS", ""), List.of("MISS", "x", ""), List.of("MISS", "", "1"), List.of("HIT", "", "1"),
            List.of("HIT", "true", "0"), List.of("HIT", "true", "-1"), List.of("HIT", "true", "01"), List.of("HIT", "true", "1.5"),
            List.of("HIT", "true", "99999999999999999999"), List.of("OK", "true", "1"), List.of("HIT", "true", 1L)));
        invalid.add(null);
        for (Object reply : invalid) {
            assertThatThrownBy(() -> CouponCacheRedisRepository.readHit(reply)).as("reply = " + reply)
                .isInstanceOf(IllegalStateException.class);
        }
    }
}
