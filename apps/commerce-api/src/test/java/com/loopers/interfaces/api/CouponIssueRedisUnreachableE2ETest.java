package com.loopers.interfaces.api;

import com.loopers.application.coupon.LocalLimiter;
import com.loopers.domain.coupon.CouponCampaignService;
import com.loopers.infrastructure.coupon.CouponJpaRepository;
import com.loopers.infrastructure.coupon.RedisSemaphoreGate;
import com.loopers.interfaces.api.coupon.CouponV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱이 접속하는 Redis 주소가 실제로 응답하지 않는 상태(연결 거부)에서도 신규 발급이 계속되는지 확인한다.
 * 아무도 듣지 않는 포트에 접속하는 실제 연결 실패이며 mock이 아니다. 컨테이너를 내렸다 올리는 장애·breaker·로컬 상한 검증은 별도 단계에서 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CouponIssueRedisUnreachableE2ETest {

    private static final int DEAD_PORT = freePort();

    @DynamicPropertySource
    static void unreachableRedis(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", () -> "localhost");
        registry.add("datasource.redis.master.port", () -> DEAD_PORT);
        registry.add("datasource.redis.replicas[0].host", () -> "localhost");
        registry.add("datasource.redis.replicas[0].port", () -> DEAD_PORT);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private TestRestTemplate testRestTemplate;

    @Autowired
    private CouponCampaignService campaignService;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    private RedisSemaphoreGate gate;

    @Autowired
    private LocalLimiter localLimiter;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ResponseEntity<ApiResponse<CouponV1Dto.IssueResponse>> issue(Long campaignId, Long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(userId));
        return testRestTemplate.exchange(
            "/api/v1/coupon-campaigns/" + campaignId + "/issues", HttpMethod.POST, new HttpEntity<>(null, headers),
            new ParameterizedTypeReference<>() {}
        );
    }

    @DisplayName("Redis에 접속할 수 없어도, 신규 발급은 201로 계속되고 재요청은 200·같은 couponId이며 내 목록은 DB에서 조회된다.")
    @Test
    void issuesContinue_whenRedisIsUnreachable() {
        // arrange
        Long campaignId = campaignService.create("장애 중 발급", 10, "혜택").getId();

        // act
        long startedAt = System.nanoTime();
        var first = issue(campaignId, 1L);
        var second = issue(campaignId, 1L);
        var other = issue(campaignId, 2L);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", "1");
        ResponseEntity<ApiResponse<CouponV1Dto.CouponListResponse>> list = testRestTemplate.exchange(
            "/api/v1/coupons", HttpMethod.GET, new HttpEntity<>(null, headers), new ParameterizedTypeReference<>() {}
        );

        // assert
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().data().couponId()).isEqualTo(first.getBody().data().couponId());
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(list.getBody().data().coupons()).extracting(CouponV1Dto.CouponResponse::couponId)
            .containsExactly(first.getBody().data().couponId());
        assertThat(couponJpaRepository.count()).isEqualTo(2);
        assertThat(elapsed).as("Redis 실패가 요청을 오래 붙잡지 않는다").isLessThan(Duration.ofSeconds(15));
        assertThat(localLimiter.inProgress()).as("직행 상한은 끝나면 반환된다").isZero();
        assertThat(gate.unreleasedTokens()).as("응답을 못 받은 자기 token을 기록해 둔다(복구 뒤 정리용)").hasSize(2);
    }
}
