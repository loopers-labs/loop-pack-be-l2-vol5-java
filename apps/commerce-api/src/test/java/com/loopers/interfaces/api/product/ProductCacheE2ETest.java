package com.loopers.interfaces.api.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.product.Price;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.PageResponse;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductCacheE2ETest {

    private static final String CUSTOMER = "/api/v1/products";
    private static final String PAGE_KEYS = "product:list:v1:brand=*";
    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;

    private static final ParameterizedTypeReference<ApiResponse<PageResponse<ProductV1Dto.ProductResponse>>> PAGE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, String> redisTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final LikeFacade likeFacade;

    private Long brandId;

    @Autowired
    ProductCacheE2ETest(
        TestRestTemplate testRestTemplate,
        JdbcTemplate jdbcTemplate,
        RedisTemplate<String, String> redisTemplate,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        LikeFacade likeFacade
    ) {
        this.testRestTemplate = testRestTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.likeFacade = likeFacade;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private Long product(String name, long price) {
        return productFacade.register(brandId, name, Price.of(price)).getId();
    }

    private PageResponse<ProductV1Dto.ProductResponse> page(Long requester, String query) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(requester));
        return testRestTemplate.exchange(CUSTOMER + query, HttpMethod.GET, new HttpEntity<>(headers), PAGE)
            .getBody().data();
    }

    private String firstPriceAscPage() {
        return "?brandId=" + brandId + "&sort=price_asc";
    }

    private Set<String> pageKeys() {
        return redisTemplate.keys(PAGE_KEYS);
    }

    private void changePriceBypassingApplication(Long productId, long price) {
        jdbcTemplate.update("UPDATE product SET price = ? WHERE id = ?", price, productId);
    }

    @DisplayName("CACHE-002 · 같은 요청의 miss 와 hit 가 같은 결과를 주고, miss 가 공용 페이지를 캐시에 남긴다")
    @Test
    void missAndHitReturnSameResult() {
        Long cheap = product("셔츠", 59_000);
        product("코트", 129_000);
        likeFacade.like(OTHER_USER, cheap);

        PageResponse<ProductV1Dto.ProductResponse> miss = page(USER, firstPriceAscPage());
        PageResponse<ProductV1Dto.ProductResponse> hit = page(USER, firstPriceAscPage());

        assertAll(
            () -> assertThat(hit).isEqualTo(miss),
            () -> assertThat(miss.items()).extracting(ProductV1Dto.ProductResponse::name).containsExactly("셔츠", "코트"),
            () -> assertThat(pageKeys()).hasSize(1)
        );
    }

    @DisplayName("CACHE-002 · hit 는 원본을 다시 읽지 않는다 — 응용 계층을 거치지 않은 DB 변경은 캐시가 비기 전까지 보이지 않는다")
    @Test
    void hitIsServedFromCache() {
        Long productId = product("셔츠", 59_000);
        page(USER, firstPriceAscPage());

        changePriceBypassingApplication(productId, 1_000);

        assertThat(page(USER, firstPriceAscPage()).items())
            .extracting(ProductV1Dto.ProductResponse::price).containsExactly(59_000L);
    }

    @DisplayName("CACHE-004 · liked 는 공용 캐시에 담기지 않는다 — 다른 사람이 채운 캐시를 읽어도 자기 좋아요만 보인다")
    @Test
    void likedIsComposedPerRequester() {
        Long productId = product("셔츠", 59_000);
        likeFacade.like(USER, productId);

        boolean byFiller = page(USER, firstPriceAscPage()).items().get(0).liked();
        boolean byOther = page(OTHER_USER, firstPriceAscPage()).items().get(0).liked();

        assertAll(
            () -> assertThat(byFiller).isTrue(),
            () -> assertThat(byOther).isFalse(),
            () -> assertThat(pageKeys()).hasSize(1)
        );
    }

    @DisplayName("CACHE-005 · 좋아요 수는 캐시에 담기지 않는다 — hit 에서도 좋아요는 바로 반영된다")
    @Test
    void likeCountIsReadFromSourceEvenOnHit() {
        Long productId = product("셔츠", 59_000);
        page(USER, firstPriceAscPage());
        changePriceBypassingApplication(productId, 1_000);

        likeFacade.like(OTHER_USER, productId);
        ProductV1Dto.ProductResponse item = page(USER, firstPriceAscPage()).items().get(0);

        assertAll(
            () -> assertThat(item.price()).as("가격은 캐시에서 — hit 였다").isEqualTo(59_000L),
            () -> assertThat(item.likeCount()).as("좋아요 수는 원본에서").isEqualTo(1L)
        );
    }

    @DisplayName("Q-2 · 가격순 · 최신순의 첫 페이지(크기 20)만 캐시한다. 인기순 · 다음 페이지 · 다른 크기는 원본으로 간다")
    @Test
    void cachesOnlyFirstPageOfPriceAndLatest() {
        product("셔츠", 59_000);

        page(USER, "?brandId=" + brandId + "&sort=likes_desc");
        page(USER, "?brandId=" + brandId + "&sort=price_asc&page=1");
        page(USER, "?brandId=" + brandId + "&sort=price_asc&size=10");
        Set<String> afterUncached = pageKeys();

        page(USER, "?brandId=" + brandId + "&sort=latest");
        page(USER, "?sort=price_asc");

        assertAll(
            () -> assertThat(afterUncached).isEmpty(),
            () -> assertThat(pageKeys()).hasSize(2)
        );
    }

    @DisplayName("CACHE-008 · 캐시에 남은 상품이 그사이 삭제됐으면 hit 에서도 보이지 않는다")
    @Test
    void hidesDeletedProductEvenOnHit() {
        Long deleted = product("양말", 1_000);
        Long kept = product("셔츠", 59_000);
        page(USER, firstPriceAscPage());

        jdbcTemplate.update("UPDATE product SET deleted_at = NOW(6) WHERE id = ?", deleted);

        assertThat(page(USER, firstPriceAscPage()).items())
            .extracting(ProductV1Dto.ProductResponse::id).containsExactly(kept);
    }
}
