package com.loopers.interfaces.api.product;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.like.LikeModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProductV1ApiE2ETest {

    private static final String ENDPOINT = "/api/v1/products";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private LikeJpaRepository likeJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private ProductModel product(BrandModel brand, String name, long price) {
        return productJpaRepository.save(new ProductModel(brand.getId(), name, price, 10));
    }

    private void like(Long userId, ProductModel product) {
        likeJpaRepository.save(new LikeModel(userId, product.getId(), ZonedDateTime.now()));
    }

    @DisplayName("GET /api/v1/products")
    @Nested
    class GetList {

        @DisplayName("기본 정렬(latest)로 브랜드 정보와 좋아요 수를 포함하고, 재고는 보여 주지 않으며, 삭제된 상품은 뺀다.")
        @Test
        void returnsProductsWithBrandAndLikes() throws Exception {
            // arrange
            BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
            ProductModel old = product(nike, "에어맥스", 100_000);
            ProductModel deleted = product(nike, "단종", 50_000);
            deleted.delete();
            productJpaRepository.save(deleted);
            ProductModel recent = product(nike, "에어포스", 120_000);
            like(1L, old);

            // act & assert
            mockMvc.perform(get(ENDPOINT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(recent.getId()))
                .andExpect(jsonPath("$.data.content[1].id").value(old.getId()))
                .andExpect(jsonPath("$.data.content[1].brand.id").value(nike.getId()))
                .andExpect(jsonPath("$.data.content[1].brand.name").value("나이키"))
                .andExpect(jsonPath("$.data.content[1].likeCount").value(1))
                .andExpect(jsonPath("$.data.content[1].stock").doesNotExist());
        }

        @DisplayName("LIK-04 likes_desc와 brandId를 함께 주면 그 브랜드 상품을 좋아요 많은 순으로 돌려준다.")
        @Test
        void sortsByLikesWithinBrand() throws Exception {
            // arrange
            BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
            BrandModel adidas = brandJpaRepository.save(new BrandModel("아디다스", null));
            ProductModel lessLiked = product(nike, "덜 인기", 1_000);
            ProductModel moreLiked = product(nike, "더 인기", 1_000);
            ProductModel otherBrand = product(adidas, "다른 브랜드", 1_000);
            like(1L, lessLiked);
            like(1L, moreLiked);
            like(2L, moreLiked);
            like(1L, otherBrand);
            like(2L, otherBrand);
            like(3L, otherBrand);

            // act & assert
            mockMvc.perform(get(ENDPOINT).param("brandId", String.valueOf(nike.getId())).param("sort", "likes_desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(moreLiked.getId()))
                .andExpect(jsonPath("$.data.content[0].likeCount").value(2))
                .andExpect(jsonPath("$.data.content[1].id").value(lessLiked.getId()));
        }

        @DisplayName("알 수 없는 sort이거나 size가 0이면 400이다.")
        @Test
        void rejectsInvalidQuery() throws Exception {
            // act & assert
            mockMvc.perform(get(ENDPOINT).param("sort", "popular"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("Bad Request"));
            mockMvc.perform(get(ENDPOINT).param("size", "0"))
                .andExpect(status().isBadRequest());
        }
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    class GetDetail {

        @DisplayName("상품 상세를 브랜드 정보·좋아요 수와 함께 돌려준다.")
        @Test
        void returnsProductDetail() throws Exception {
            // arrange
            BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
            ProductModel product = product(nike, "에어맥스", 100_000);
            like(1L, product);
            like(2L, product);

            // act & assert
            mockMvc.perform(get(ENDPOINT + "/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("에어맥스"))
                .andExpect(jsonPath("$.data.price").value(100_000))
                .andExpect(jsonPath("$.data.brand.name").value("나이키"))
                .andExpect(jsonPath("$.data.likeCount").value(2));
        }

        @DisplayName("PRD-06 삭제된 상품이면 404다.")
        @Test
        void returnsNotFound_whenDeleted() throws Exception {
            // arrange
            ProductModel product = product(brandJpaRepository.save(new BrandModel("나이키", null)), "단종", 1_000);
            product.delete();
            productJpaRepository.save(product);

            // act & assert
            mockMvc.perform(get(ENDPOINT + "/" + product.getId()))
                .andExpect(status().isNotFound());
        }
    }
}
