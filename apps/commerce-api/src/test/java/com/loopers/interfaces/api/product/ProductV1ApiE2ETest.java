package com.loopers.interfaces.api.product;

import com.loopers.application.like.LikeFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductV1ApiE2ETest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private Brand brand;

    @BeforeEach
    void setUp() {
        brand = brandJpaRepository.save(new Brand("브랜드", "브랜드 설명"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        // 목록 첫 페이지는 캐시된다. DB 를 비우면 식별자가 다시 쓰이므로 캐시도 함께 비운다 (4주차 cache-design.md)
        redisCleanUp.truncateAll();
    }

    // 모든 상품이 좋아요 수 행을 갖도록 생성 입구로 만든다 (4주차 설계 2.2)
    private Product saveProduct(String name, int stock) {
        Product product = productService.create(brand, name, 1_000L);
        return productService.changeStock(product.getId(), stock).product();
    }

    @DisplayName("GET /api/v1/brands/{brandId}")
    @Nested
    class GetBrand {
        @DisplayName("식별 없이, 브랜드의 식별자 · 이름 · 설명을 돌려준다.")
        @Test
        void returnsBrand() throws Exception {
            mvc.perform(get("/api/v1/brands/" + brand.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.name").value("브랜드"))
                .andExpect(jsonPath("$.data.description").value("브랜드 설명"))
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
        }

        @DisplayName("삭제된 브랜드면, 404 와 BRAND_NOT_FOUND 를 돌려준다.")
        @Test
        void returnsNotFound_whenBrandIsDeleted() throws Exception {
            brand.delete();
            brandJpaRepository.save(brand);

            mvc.perform(get("/api/v1/brands/" + brand.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        }
    }

    @DisplayName("GET /api/v1/products")
    @Nested
    class GetProducts {
        @DisplayName("식별 없이, 좋아요 수 · 품절 여부 · 브랜드를 담은 목록을 돌려주고 재고 수량은 내보내지 않는다.")
        @Test
        void returnsProductSummaries() throws Exception {
            Product product = saveProduct("상품", 0);
            likeFacade.like(1L, product.getId());

            mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(product.getId()))
                .andExpect(jsonPath("$.data.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.data.items[0].soldOut").value(true))
                .andExpect(jsonPath("$.data.items[0].stock").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].brand.id").value(brand.getId()))
                .andExpect(jsonPath("$.data.items[0].brand.name").value("브랜드"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.totalCount").value(1));
        }

        @DisplayName("likes_desc 로 정렬하면, 좋아요 수가 많은 상품이 먼저 온다.")
        @Test
        void sortsByLikes() throws Exception {
            Product popular = saveProduct("인기 상품", 1);
            Product plain = saveProduct("보통 상품", 1);
            likeFacade.like(1L, popular.getId());

            mvc.perform(get("/api/v1/products").param("sort", "likes_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(popular.getId()))
                .andExpect(jsonPath("$.data.items[1].id").value(plain.getId()));
        }

        @DisplayName("모르는 정렬 값이면, 기본값으로 바꾸지 않고 400 과 BAD_REQUEST 를 돌려준다.")
        @Test
        void rejectsUnknownSort() throws Exception {
            mvc.perform(get("/api/v1/products").param("sort", "popular"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BAD_REQUEST"));
        }

        @DisplayName("없는 브랜드로 거르면, 404 가 아니라 빈 목록을 돌려준다. (D-24)")
        @Test
        void returnsEmpty_whenFilterBrandDoesNotExist() throws Exception {
            saveProduct("상품", 1);

            mvc.perform(get("/api/v1/products").param("brandId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalCount").value(0));
        }
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    class GetProduct {
        @DisplayName("브랜드 설명까지 담은 상세를 돌려준다.")
        @Test
        void returnsDetail() throws Exception {
            Product product = saveProduct("상품", 3);

            mvc.perform(get("/api/v1/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("상품"))
                .andExpect(jsonPath("$.data.likeCount").value(0))
                .andExpect(jsonPath("$.data.soldOut").value(false))
                .andExpect(jsonPath("$.data.brand.description").value("브랜드 설명"));
        }

        @DisplayName("삭제된 상품이면, 404 와 PRODUCT_NOT_FOUND 를 돌려준다.")
        @Test
        void returnsNotFound_whenProductIsDeleted() throws Exception {
            Product product = saveProduct("상품", 3);
            product.delete();
            productJpaRepository.save(product);

            mvc.perform(get("/api/v1/products/" + product.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        }
    }
}
