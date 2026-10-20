package com.loopers.seed;

import com.loopers.product.application.port.out.ProductQueryPort;
import com.loopers.product.domain.ProductSort;
import com.loopers.product.domain.ProductWithLikes;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * W4 · 측정용 seed가 과제가 준 검증값과 맞는지, 그리고 실제 조회 모델이 그대로 읽을 수 있는지 확인한다.
 * 기대값은 seed 식을 다시 계산하지 않고 숫자로 적는다 — 식이 바뀌면 이 테스트가 알려 준다.
 */
@SpringBootTest
class CatalogSeederIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProductQueryPort productQueryPort;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private CatalogSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new CatalogSeeder(jdbcTemplate);
        seeder.seed(100, 10_000, 1_000);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    @DisplayName("상품 10,000개의 개수와 가격 합계가 과제의 검증값과 같다.")
    @Test
    void matchesAssignmentChecksum() {
        assertThat(count("SELECT COUNT(*) FROM products")).isEqualTo(10_000);
        assertThat(count("SELECT SUM(price) FROM products")).isEqualTo(472_685_000);
        assertThat(count("SELECT COUNT(*) FROM products WHERE brand_id = 7")).isEqualTo(100);
        assertThat(count("SELECT SUM(price) FROM products WHERE brand_id = 7")).isEqualTo(4_647_200);
    }

    @DisplayName("브랜드·삭제·품절·좋아요 수가 한 값에 몰리지 않는다.")
    @Test
    void spreadsValues() {
        assertThat(count("SELECT COUNT(DISTINCT brand_id) FROM products")).isEqualTo(100);
        assertThat(count("SELECT COUNT(*) FROM products WHERE deleted_at IS NOT NULL")).isEqualTo(103);
        assertThat(count("SELECT COUNT(*) FROM products WHERE stock = 0")).isEqualTo(200);

        assertThat(count("SELECT COUNT(*) FROM likes")).isEqualTo(90_630);
        // 좋아요 0개: 10번째마다 1,000개 + 나머지 중 0이 나온 450개
        assertThat(count("SELECT COUNT(*) FROM products p WHERE NOT EXISTS (SELECT 1 FROM likes l WHERE l.product_id = p.id)"))
            .isEqualTo(1_450);
        // 동점: 좋아요 19개인 상품이 450개
        assertThat(count("SELECT COUNT(*) FROM (SELECT product_id FROM likes GROUP BY product_id HAVING COUNT(*) = 19) t"))
            .isEqualTo(450);
        // 인기 상품: 9001번이 750개로 가장 많다
        assertThat(count("SELECT COUNT(*) FROM likes WHERE product_id = 9001")).isEqualTo(750);
        assertThat(count("SELECT MAX(c) FROM (SELECT COUNT(*) c FROM likes GROUP BY product_id) t")).isEqualTo(750);
    }

    @DisplayName("실제 조회 모델이 seed를 읽는다 — 브랜드 7의 가격순 첫 페이지.")
    @Test
    void isReadableByRealQuery() {
        Page<ProductWithLikes> page = productQueryPort.findSellable(7L, ProductSort.PRICE_ASC, PageRequest.of(0, 20));

        // 브랜드 7의 100개 중 삭제된 9506번 하나가 빠진다
        assertThat(page.getTotalElements()).isEqualTo(99);
        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent().subList(0, 3))
            .extracting(p -> p.product().getId(), p -> p.product().getPrice().amount(), ProductWithLikes::likeCount)
            .containsExactly(
                tuple(5406L, 122L, 4L),
                tuple(2706L, 222L, 3L),
                tuple(6L, 322L, 2L)
            );
    }

    @DisplayName("이미 데이터가 있으면 seed를 거절하고 아무것도 더하지 않는다.")
    @Test
    void rejectsSecondSeed() {
        assertThatThrownBy(() -> seeder.seed(100, 10_000, 1_000)).isInstanceOf(IllegalStateException.class);

        assertThat(count("SELECT COUNT(*) FROM products")).isEqualTo(10_000);
        assertThat(count("SELECT COUNT(*) FROM likes")).isEqualTo(90_630);
    }
}
