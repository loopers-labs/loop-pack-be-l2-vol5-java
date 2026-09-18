package com.loopers.interfaces.api.like;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LikeApiE2ETest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductLikeRepository likeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FixtureUserInitializer initializer;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Brand brand;
    private Product product;

    @BeforeEach
    void setUp() {
        initializer.initialize();
        brand = brandRepository.save(new Brand("좋아요 브랜드"));
        product = productRepository.save(new Product(brand, "좋아요 상품", 1000L, 5));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("API-05/C04·C05·C06: 등록·중복·취소·재취소·재등록은 3·3·2·2·3개이며 타인 관계를 보존한다.")
    @Test
    void registersCancelsAndReregistersOnlyRequestersRelation() {
        seedLike(2L, product);
        seedLike(3L, product);

        assertLike(mutate(HttpMethod.POST, "alice", product.getId()), true, 3L);
        assertOwnRelationCount(1L);
        JsonNode ownList = success(list("alice", "alice", ""));
        assertThat(ownList.path("totalElements").asLong()).isEqualTo(1L);
        assertThat(ownList.path("items").get(0).path("productId").asLong()).isEqualTo(product.getId());
        assertThat(ownList.path("items").get(0).path("likeCount").asLong()).isEqualTo(3L);
        var beforeDuplicate = jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id");

        assertLike(mutate(HttpMethod.POST, "alice", product.getId()), true, 3L);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id")).isEqualTo(beforeDuplicate);
        assertLike(mutate(HttpMethod.DELETE, "alice", product.getId()), false, 2L);
        assertOwnRelationCount(0L);
        assertThat(success(list("alice", "alice", "")).path("items")).isEmpty();
        assertLike(mutate(HttpMethod.DELETE, "alice", product.getId()), false, 2L);
        assertOwnRelationCount(0L);
        assertLike(mutate(HttpMethod.POST, "alice", product.getId()), true, 3L);
        assertOwnRelationCount(1L);
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM `like` WHERE user_id <> 1 ORDER BY user_id", Long.class))
            .containsExactly(2L, 3L);
    }

    @DisplayName("API-06/C04·C05·C06: 삭제 상품 관계를 보존하되 신규 등록·목록에서 제외하고 본인 취소는 허용한다.")
    @Test
    void hidesDeletedProductButAllowsCancellingRemainingRelation() {
        seedLike(1L, product);
        seedLike(2L, product);
        jdbcTemplate.update("UPDATE product SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", product.getId());
        var before = jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id");

        assertFailure(mutate(HttpMethod.POST, "alice", product.getId()), HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        assertThat(success(list("alice", "alice", "")).path("items")).isEmpty();
        assertThat(jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id")).isEqualTo(before);
        assertLike(mutate(HttpMethod.DELETE, "alice", product.getId()), false, 1L);
        assertOwnRelationCount(0L);
        assertThat(likeRepository.existsByUserIdAndProductId(2L, product.getId())).isTrue();
    }

    @DisplayName("C04·C06: 소속 브랜드가 삭제된 상품은 등록·본인 목록에서 제외한다.")
    @Test
    void rejectsProductWithDeletedBrandAndHidesItFromList() {
        seedLike(1L, product);
        jdbcTemplate.update("UPDATE brand SET deleted_at = UTC_TIMESTAMP(6) WHERE id = ?", brand.getId());

        assertFailure(mutate(HttpMethod.POST, "bob", product.getId()), HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        assertThat(success(list("alice", "alice", "")).path("totalElements").asLong()).isZero();
        assertThat(likeRepository.countByProductId(product.getId())).isEqualTo(1L);
        assertLike(mutate(HttpMethod.DELETE, "alice", product.getId()), false, 0L);
    }

    @DisplayName("C06: 내 관계의 생성시각·관계 ID 내림차순으로 필터 후 페이지를 자른다.")
    @Test
    void listsOwnLikesWithDeterministicOrderAndPagination() {
        Product second = productRepository.save(new Product(brand, "두 번째", 2000L, 3));
        Product third = productRepository.save(new Product(brand, "세 번째", 3000L, 4));
        seedLike(1L, third);
        seedLike(1L, product);
        seedLike(1L, second);
        seedLike(2L, third);
        jdbcTemplate.update("UPDATE `like` SET created_at = '2026-09-18 00:00:00.000000'");
        var before = jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id");

        JsonNode firstPage = success(list("alice", "alice", "?page=0&size=2"));
        assertThat(firstPage.path("page").asInt()).isZero();
        assertThat(firstPage.path("size").asInt()).isEqualTo(2);
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(3L);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.path("items").findValuesAsText("productId"))
            .containsExactly(second.getId().toString(), product.getId().toString());
        JsonNode secondPage = success(list("alice", "alice", "?page=1&size=2"));
        assertThat(secondPage.path("items").findValuesAsText("productId")).containsExactly(third.getId().toString());
        assertThat(success(list("alice", "alice", "?page=2&size=2")).path("items")).isEmpty();
        assertThat(success(list("bob", "bob", "")).path("items").findValuesAsText("productId"))
            .containsExactly(third.getId().toString());
        assertThat(jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id")).isEqualTo(before);
    }

    @DisplayName("C06: 다른 사용자·없는 사용자의 좋아요 목록은 동일한 404다.")
    @ParameterizedTest
    @ValueSource(strings = {"bob", "unknown", "Alice", "1"})
    void rejectsOtherUsersLikeList(String owner) {
        seedLike(1L, product);

        assertFailure(list("alice", owner, ""), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        assertOwnRelationCount(1L);
    }

    @DisplayName("C04·C05: 실제로 없는 상품에 등록·취소하면 404이며 기존 관계를 보존한다.")
    @Test
    void rejectsMissingProductForRegistrationAndCancellation() {
        seedLike(1L, product);

        assertFailure(mutate(HttpMethod.POST, "alice", Long.MAX_VALUE), HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        assertFailure(mutate(HttpMethod.DELETE, "alice", Long.MAX_VALUE), HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");

        assertOwnRelationCount(1L);
    }

    @DisplayName("C04·C05: 상품 ID 형식은 사용자 식별보다 먼저 검증한다.")
    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1", "1.5", "9223372036854775808"})
    void rejectsInvalidProductIdBeforeResolvingUser(String productId) {
        for (HttpMethod method : List.of(HttpMethod.POST, HttpMethod.DELETE)) {
            assertFailure(request("/api/v1/products/" + productId + "/likes", method, "unknown"),
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        assertOwnRelationCount(0L);
    }

    @DisplayName("API-04/C06: 잘못된 페이지·크기는 400이고 저장값을 보존한다.")
    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?page=abc", "?page=1.5", "?page=2147483648", "?size=0", "?size=101", "?size=1.5"})
    void rejectsInvalidPaging(String query) {
        seedLike(1L, product);

        assertFailure(list("alice", "alice", query), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");

        assertOwnRelationCount(1L);
    }

    @DisplayName("C04·C05·C06: 사용자 헤더 누락·빈 값은 400이다.")
    @ParameterizedTest
    @NullAndEmptySource
    void rejectsMissingUserHeader(String requester) {
        assertFailure(mutate(HttpMethod.POST, requester, product.getId()), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertFailure(mutate(HttpMethod.DELETE, requester, product.getId()), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertFailure(list(requester, "alice", ""), HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        assertOwnRelationCount(0L);
    }

    @DisplayName("C04·C05·C06: 매핑된 사용자 행이 없어도 요청에서 생성하지 않는다.")
    @Test
    void rejectsMissingUserRowWithoutCreatingIt() {
        jdbcTemplate.update("DELETE FROM `user` WHERE id = 1");

        assertFailure(mutate(HttpMethod.POST, "alice", product.getId()), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertFailure(mutate(HttpMethod.DELETE, "alice", product.getId()), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertFailure(list("alice", "alice", ""), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `user` WHERE id = 1", Long.class)).isZero();
    }

    @DisplayName("API-23/C04: 같은 사용자의 동시 중복 좋아요는 둘 다 성공하고 한 관계만 저장한다.")
    @Test
    void serializesConcurrentDuplicateRegistration() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return mutate(HttpMethod.POST, "alice", product.getId());
            });
            var second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return mutate(HttpMethod.POST, "alice", product.getId());
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertLike(first.get(10, TimeUnit.SECONDS), true, 1L);
            assertLike(second.get(10, TimeUnit.SECONDS), true, 1L);
        }
        assertOwnRelationCount(1L);
    }

    private void seedLike(long userId, Product target) {
        likeRepository.save(new ProductLike(userRepository.findById(userId).orElseThrow(), target));
    }

    private ResponseEntity<JsonNode> mutate(HttpMethod method, String requester, long productId) {
        return request("/api/v1/products/" + productId + "/likes", method, requester);
    }

    private ResponseEntity<JsonNode> list(String requester, String userId, String query) {
        return request("/api/v1/users/" + userId + "/likes" + query, HttpMethod.GET, requester);
    }

    private ResponseEntity<JsonNode> request(String path, HttpMethod method, String requester) {
        HttpHeaders headers = new HttpHeaders();
        if (requester != null) {
            headers.set("X-USER-ID", requester);
        }
        return restTemplate.exchange(path, method, new HttpEntity<>(headers), JsonNode.class);
    }

    private JsonNode success(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("meta").path("result").asText()).isEqualTo("SUCCESS");
        assertThat(body.path("meta").size()).isEqualTo(1);
        assertThat(body.size()).isEqualTo(2);
        return body.path("data");
    }

    private void assertLike(ResponseEntity<JsonNode> response, boolean liked, long count) {
        JsonNode data = success(response);
        assertAll(
            () -> assertThat(data.size()).isEqualTo(3),
            () -> assertThat(data.path("productId").asLong()).isEqualTo(product.getId()),
            () -> assertThat(data.path("liked").isBoolean()).isTrue(),
            () -> assertThat(data.path("liked").asBoolean()).isEqualTo(liked),
            () -> assertThat(data.path("likeCount").asLong()).isEqualTo(count)
        );
    }

    private void assertFailure(ResponseEntity<JsonNode> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(body.path("meta").path("errorCode").asText()).isEqualTo(code);
        assertThat(body.path("meta").size()).isEqualTo(3);
        assertThat(body.has("data")).isFalse();
    }

    private void assertOwnRelationCount(long expected) {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM `like` WHERE user_id = 1 AND product_id = ?", Long.class, product.getId()))
            .isEqualTo(expected);
    }
}
