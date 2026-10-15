package com.loopers.application.product;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.utils.DatabaseCleanUp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// like_count를 관계 수와 다르게 직접 넣어, 정렬과 표시가 모두 products.like_count를 읽는지 확인한다(설계 2·18절).
@SpringBootTest
class ProductSearchIntegrationTest {
    @Autowired private BrandApplicationService brandApplicationService;
    @Autowired private ProductApplicationService productApplicationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private final Map<String, Long> ids = new HashMap<>();
    private long brandA;

    // 이름 가격 좋아요수 생성시각. a4는 삭제 상품으로 모든 정렬에서 맨 앞에 올 값을 줘 제외 여부를 드러낸다.
    @BeforeEach
    void prepare() {
        brandA = brandApplicationService.create("브랜드A").id().value();
        long brandB = brandApplicationService.create("브랜드B").id().value();
        create(brandA, "a1", 300, 5, "2025-01-01 00:00:01");
        create(brandA, "a2", 100, 2, "2025-01-01 00:00:03");
        create(brandA, "a3", 100, 2, "2025-01-01 00:00:03");
        create(brandA, "a4", 50, 9, "2025-01-01 00:00:09");
        create(brandB, "b1", 100, 5, "2025-01-01 00:00:02");
        create(brandB, "b2", 200, 0, "2025-01-01 00:00:04");
        productApplicationService.delete(ids.get("a4"));
    }

    @AfterEach
    void clean() {
        databaseCleanUp.truncateAllTables();
    }

    static Stream<Arguments> sortCases() {
        return Stream.of(
            Arguments.of("likes_desc", false, List.of("b1", "a1", "a3", "a2", "b2")),
            Arguments.of("likes_desc", true, List.of("a1", "a3", "a2")),
            Arguments.of("price_asc", false, List.of("b1", "a3", "a2", "b2", "a1")),
            Arguments.of("price_asc", true, List.of("a3", "a2", "a1")),
            Arguments.of("latest", false, List.of("b2", "a3", "a2", "b1", "a1")),
            Arguments.of("latest", true, List.of("a3", "a2", "a1")));
    }

    @ParameterizedTest(name = "정렬={0}, 브랜드A 필터={1} → {2}")
    @MethodSource("sortCases")
    @DisplayName("목록은 정렬 기준과 동률 ID 내림차순을 따르고 삭제 상품을 빼며 표시 좋아요 수는 like_count다")
    void sortsAndShowsLikeCount(String sort, boolean filterBrand, List<String> expectedNames) {
        List<CustomerProductResult> found = productApplicationService.search(filterBrand ? brandA : null, 0, 20, sort);

        assertThat(found).extracting(CustomerProductResult::name).containsExactlyElementsOf(expectedNames);
        assertThat(found).allSatisfy(product -> assertThat(product.likeCount()).isEqualTo(likeCount(product.id())));
    }

    @Test
    @DisplayName("좋아요 순 page=2·size=2는 전체 정렬 뒤 잘린 마지막 구간 한 건을 돌려준다")
    void pagesAfterSorting() {
        List<CustomerProductResult> found = productApplicationService.search(null, 2, 2, "likes_desc");

        assertThat(found).extracting(CustomerProductResult::name).containsExactly("b2");
    }

    @Test
    @DisplayName("상품 상세의 좋아요 수는 like_count다")
    void showsLikeCountOnDetail() {
        CustomerProductResult found = productApplicationService.getProduct(ids.get("a1"));

        assertThat(found.likeCount()).isEqualTo(5);
    }

    private void create(long brandId, String name, long price, long likeCount, String createdAt) {
        long id = productApplicationService.create(brandId, name, price, 1).id();
        jdbcTemplate.update("update products set like_count = ?, created_at = ? where id = ?", likeCount, createdAt, id);
        ids.put(name, id);
    }

    private long likeCount(long productId) {
        return jdbcTemplate.queryForObject("select like_count from products where id = ?", Long.class, productId);
    }
}
