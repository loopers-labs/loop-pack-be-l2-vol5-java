package com.loopers.acceptance;

import com.loopers.utils.RedisCleanUp;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 상품 응답을 바꾸는 쓰기 경로 — 변경이 끝난 뒤 새로 시작한 조회는 변경된 결과를 본다 (docs/week4/design.md 3·7번 섹션).
 *
 * <p>모든 시나리오는 "먼저 조회 → 변경 → 다시 조회" 순서다. 캐시가 붙으면 첫 조회가 캐시를 채우므로,
 * 이 순서가 곧 "변경 뒤에 오래된 복사본을 보지 않는다"(인수 조건 ①)를 검증한다. A3~A8.
 * 예외: A7(좋아요)은 캐시를 지우지 않고 TTL(60초)로 반영하기로 계약을 바꿔, 변경 뒤 Redis를 비워 캐시 만료를 흉내 낸 다음 조회한다
 * (docs/week4/design.md 6번 섹션 "계약 변경 — 좋아요 반영 지연").
 */
@DisplayName("상품 변경 반영")
class ProductChangeAcceptanceTest extends AcceptanceTest {

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Long brand;
    private Long otherBrand;
    private Long cheap;
    private Long middle;
    private Long expensive;
    private Long other;

    @BeforeEach
    void setUp() {
        brand = AdminSteps.createBrand("나이키");
        otherBrand = AdminSteps.createBrand("아디다스");
        cheap = AdminSteps.createProduct(brand, "슬리퍼", 10_000L, 10);
        middle = AdminSteps.createProduct(brand, "티셔츠", 20_000L, 10);
        expensive = AdminSteps.createProduct(brand, "운동화", 30_000L, 10);
        other = AdminSteps.createProduct(otherBrand, "모자", 15_000L, 10);
    }

    private static List<Long> priceAscOf(Long brandId) {
        return CustomerSteps.getProductIds(Map.of("brandId", brandId, "sort", "price_asc"));
    }

    @DisplayName("A3 — 관리자가 가격·이름을 바꾸면, 상세·목록과 가격순 위치에 반영된다.")
    @Test
    void updatePriceAndName_reflected() {
        assertThat(priceAscOf(brand)).containsExactly(cheap, middle, expensive);
        CustomerSteps.getProduct(cheap);

        AdminSteps.updateProduct(cheap, "프리미엄 슬리퍼", 50_000L);

        ExtractableResponse<Response> detail = CustomerSteps.getProduct(cheap);
        ExtractableResponse<Response> list = CustomerSteps.getProducts(Map.of("brandId", brand, "sort", "price_asc"));
        assertAll(
            () -> assertThat(detail.jsonPath().getString("data.name")).isEqualTo("프리미엄 슬리퍼"),
            () -> assertThat(detail.jsonPath().getLong("data.price")).isEqualTo(50_000L),
            () -> assertThat(list.jsonPath().getList("data.products.id", Long.class)).containsExactly(middle, expensive, cheap),
            () -> assertThat(list.jsonPath().getLong("data.products[2].price")).isEqualTo(50_000L)
        );
    }

    @DisplayName("A4 — 관리자가 상품을 등록하면 목록에 나타나고, 삭제하면 사라진다.")
    @Test
    void createAndDelete_reflected() {
        assertThat(priceAscOf(brand)).containsExactly(cheap, middle, expensive);

        Long added = AdminSteps.createProduct(brand, "양말", 5_000L, 10);
        assertThat(priceAscOf(brand)).containsExactly(added, cheap, middle, expensive);

        AdminSteps.deleteProduct(added);
        ExtractableResponse<Response> list = CustomerSteps.getProducts(Map.of("brandId", brand, "sort", "price_asc"));
        assertAll(
            () -> assertThat(list.jsonPath().getList("data.products.id", Long.class)).containsExactly(cheap, middle, expensive),
            () -> assertThat(list.jsonPath().getLong("data.totalElements")).isEqualTo(3L),
            () -> assertThat(CustomerSteps.getProduct(added).statusCode()).isEqualTo(404)
        );
    }

    @DisplayName("A5 — 관리자가 브랜드 이름을 바꾸면, 상품 목록·상세의 브랜드명에 반영된다.")
    @Test
    void renameBrand_reflectedInProducts() {
        CustomerSteps.getProducts(Map.of("brandId", brand));
        CustomerSteps.getProduct(cheap);

        AdminSteps.renameBrand(brand, "NIKE");

        ExtractableResponse<Response> list = CustomerSteps.getProducts(Map.of("brandId", brand));
        assertAll(
            () -> assertThat(list.jsonPath().getList("data.products.brand.name", String.class)).containsOnly("NIKE"),
            () -> assertThat(CustomerSteps.getProduct(cheap).jsonPath().getString("data.brand.name")).isEqualTo("NIKE")
        );
    }

    @DisplayName("A6 — 관리자가 브랜드를 삭제하면 그 브랜드의 상품은 사라지고, 다른 브랜드 상품은 그대로다.")
    @Test
    void deleteBrand_removesOnlyItsProducts() {
        assertThat(CustomerSteps.getProductIds(Map.of("sort", "price_asc"))).containsExactly(cheap, other, middle, expensive);

        AdminSteps.deleteBrand(brand);

        assertAll(
            () -> assertThat(CustomerSteps.getProductIds(Map.of("sort", "price_asc"))).containsExactly(other),
            () -> assertThat(CustomerSteps.getProduct(cheap).statusCode()).isEqualTo(404),
            () -> assertThat(CustomerSteps.getProduct(other).statusCode()).isEqualTo(200)
        );
    }

    @DisplayName("A7 — 좋아요 등록·취소는 캐시가 만료되면(=Redis 비우기로 흉내 낸다) 좋아요 수와 likes_desc 순서에 반영된다. 설계 6번 섹션 계약 변경.")
    @Test
    void likeAndUnlike_reflected() {
        Long user = givenUser();
        // 좋아요가 모두 0이면 이름순(슬리퍼 < 운동화 < 티셔츠)
        List<Long> before = CustomerSteps.getProductIds(Map.of("brandId", brand, "sort", "likes_desc"));
        assertThat(before).containsExactly(cheap, expensive, middle);

        CustomerSteps.like(user, middle);
        // 캐시 만료(=Redis 비우기)를 흉내 낸다 — 좋아요는 캐시를 지우지 않고 TTL(60초)로 반영된다 (설계 6번 섹션 계약 변경)
        redisCleanUp.truncateAll();

        assertAll(
            () -> assertThat(CustomerSteps.getProductIds(Map.of("brandId", brand, "sort", "likes_desc")))
                .containsExactly(middle, cheap, expensive),
            () -> assertThat(CustomerSteps.getProduct(middle).jsonPath().getLong("data.likeCount")).isEqualTo(1L)
        );

        CustomerSteps.unlike(user, middle);
        // 취소도 같다 — 캐시 만료(=Redis 비우기)를 흉내 낸다 (설계 6번 섹션 계약 변경)
        redisCleanUp.truncateAll();

        assertAll(
            () -> assertThat(CustomerSteps.getProductIds(Map.of("brandId", brand, "sort", "likes_desc")))
                .containsExactly(cheap, expensive, middle),
            () -> assertThat(CustomerSteps.getProduct(middle).jsonPath().getLong("data.likeCount")).isEqualTo(0L)
        );
    }

    @DisplayName("A8 — 주문 확정과 관리자 재고 변경은 남은 재고에 반영된다.")
    @Test
    void stockChanges_reflected() {
        Long user = givenUser();
        CustomerSteps.chargePoint(user, 100_000L);
        assertThat(CustomerSteps.getProduct(cheap).jsonPath().getInt("data.remainingStock")).isEqualTo(10);

        Long orderId = CustomerSteps.createOrder(user, cheap, 3).jsonPath().getLong("data.id");
        CustomerSteps.confirmOrder(user, orderId);
        assertThat(CustomerSteps.getProduct(cheap).jsonPath().getInt("data.remainingStock")).isEqualTo(7);

        AdminSteps.changeStock(cheap, 50);
        ExtractableResponse<Response> list = CustomerSteps.getProducts(Map.of("brandId", brand, "sort", "price_asc", "size", 1));
        assertAll(
            () -> assertThat(CustomerSteps.getProduct(cheap).jsonPath().getInt("data.remainingStock")).isEqualTo(50),
            () -> assertThat(list.jsonPath().getInt("data.products[0].remainingStock")).isEqualTo(50)
        );
    }
}
