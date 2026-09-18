package com.loopers.interfaces.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminCrudV1ApiE2ETest {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public AdminCrudV1ApiE2ETest(
        MockMvc mockMvc,
        ObjectMapper objectMapper,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드를 수정할 때, ")
    @Nested
    class UpdateBrand {
        @DisplayName("유효한 이름이면, 200과 수정된 브랜드를 받는다.")
        @Test
        void returnsUpdatedBrand_whenNameIsValid() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();

            // act
            adminPut("/api-admin/v1/brands/" + brandId, "{\"name\":\"새 이름\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새 이름"));

            // assert
            assertThat(brandJpaRepository.findById(brandId).orElseThrow().getName()).isEqualTo("새 이름");
        }

        @DisplayName("빈 이름이면, 400 응답을 받고 기존 이름이 유지된다.")
        @Test
        void keepsName_whenNameIsBlank() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();

            // act
            adminPut("/api-admin/v1/brands/" + brandId, "{\"name\":\"   \"}")
                .andExpect(status().isBadRequest());

            // assert
            assertThat(brandJpaRepository.findById(brandId).orElseThrow().getName()).isEqualTo("루퍼스");
        }

        @DisplayName("삭제된 브랜드이면, 404 응답을 받는다.")
        @Test
        void returnsNotFound_whenBrandIsDeleted() throws Exception {
            // arrange
            Brand brand = new Brand("루퍼스");
            brand.delete();
            Long brandId = brandJpaRepository.save(brand).getId();

            // act & assert
            adminPut("/api-admin/v1/brands/" + brandId, "{\"name\":\"새 이름\"}")
                .andExpect(status().isNotFound());
        }
    }

    @DisplayName("브랜드를 삭제할 때, ")
    @Nested
    class DeleteBrand {
        @DisplayName("연결된 상품이 없으면, 204 응답을 받고 논리 삭제된다.")
        @Test
        void softDeletesBrand_whenNoProductRemains() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();

            // act
            adminDelete("/api-admin/v1/brands/" + brandId).andExpect(status().isNoContent());

            // assert - 레코드는 남고 deletedAt 만 기록된다
            Brand found = brandJpaRepository.findById(brandId).orElseThrow();
            assertThat(found.getDeletedAt()).isNotNull();
            assertThat(found.getName()).isEqualTo("루퍼스");
        }

        @DisplayName("삭제되지 않은 연결 상품이 있으면, 409 응답을 받고 삭제되지 않는다.")
        @Test
        void returnsConflict_whenActiveProductRemains() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            productJpaRepository.save(new Product(brandId, "연결 상품", new Price(1000L)));

            // act
            adminDelete("/api-admin/v1/brands/" + brandId).andExpect(status().isConflict());

            // assert
            assertThat(brandJpaRepository.findById(brandId).orElseThrow().getDeletedAt()).isNull();
        }

        @DisplayName("재고가 0인 상품만 남아 있어도, 409 응답을 받는다.")
        @Test
        void returnsConflict_whenOnlyOutOfStockProductRemains() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            productJpaRepository.save(new Product(brandId, "품절 상품", new Price(1000L)));

            // act & assert - 재고 0 은 품절일 뿐 삭제된 상품이 아니다
            adminDelete("/api-admin/v1/brands/" + brandId).andExpect(status().isConflict());
        }

        @DisplayName("연결 상품이 모두 삭제되었으면, 204 응답을 받는다.")
        @Test
        void softDeletesBrand_whenAllProductsAreDeleted() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            Product product = new Product(brandId, "삭제된 상품", new Price(1000L));
            product.delete();
            productJpaRepository.save(product);

            // act & assert
            adminDelete("/api-admin/v1/brands/" + brandId).andExpect(status().isNoContent());
        }

        @DisplayName("이미 삭제된 브랜드이면, 404 응답을 받는다.")
        @Test
        void returnsNotFound_whenAlreadyDeleted() throws Exception {
            // arrange
            Brand brand = new Brand("루퍼스");
            brand.delete();
            Long brandId = brandJpaRepository.save(brand).getId();

            // act & assert
            adminDelete("/api-admin/v1/brands/" + brandId).andExpect(status().isNotFound());
        }
    }

    @DisplayName("상품을 등록할 때, ")
    @Nested
    class CreateProduct {
        @DisplayName("브랜드가 유효하면, 201과 등록된 상품을 받는다.")
        @Test
        void returnsCreatedProduct_whenBrandIsValid() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();

            // act
            adminPost("/api-admin/v1/products",
                "{\"brandId\":" + brandId + ",\"name\":\"티셔츠\",\"price\":1000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.productName").value("티셔츠"));

            // assert
            assertThat(productJpaRepository.findAll()).hasSize(1);
        }

        @DisplayName("삭제된 브랜드에 등록하면, 404 응답을 받고 저장되지 않는다.")
        @Test
        void returnsNotFound_whenBrandIsDeleted() throws Exception {
            // arrange
            Brand brand = new Brand("루퍼스");
            brand.delete();
            Long brandId = brandJpaRepository.save(brand).getId();

            // act
            adminPost("/api-admin/v1/products",
                "{\"brandId\":" + brandId + ",\"name\":\"티셔츠\",\"price\":1000}")
                .andExpect(status().isNotFound());

            // assert
            assertThat(productJpaRepository.findAll()).isEmpty();
        }
    }

    @DisplayName("상품을 수정할 때, ")
    @Nested
    class UpdateProduct {
        @DisplayName("이름과 가격이 유효하면, 200과 수정된 상품을 받고 브랜드는 유지된다.")
        @Test
        void keepsBrand_whenUpdated() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            Long productId = productJpaRepository.save(new Product(brandId, "티셔츠", new Price(1000L))).getId();

            // act
            adminPut("/api-admin/v1/products/" + productId, "{\"name\":\"새 이름\",\"price\":2000}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productName").value("새 이름"));

            // assert
            Product found = productJpaRepository.findById(productId).orElseThrow();
            assertThat(found.getPrice().getAmount()).isEqualTo(2000L);
            assertThat(found.getBrandId()).isEqualTo(brandId);
        }

        @DisplayName("삭제된 상품이면, 404 응답을 받는다.")
        @Test
        void returnsNotFound_whenProductIsDeleted() throws Exception {
            // arrange
            Product product = new Product(1L, "티셔츠", new Price(1000L));
            product.delete();
            Long productId = productJpaRepository.save(product).getId();

            // act & assert
            adminPut("/api-admin/v1/products/" + productId, "{\"name\":\"새 이름\",\"price\":2000}")
                .andExpect(status().isNotFound());
        }
    }

    @DisplayName("상품을 삭제할 때, ")
    @Nested
    class DeleteProduct {
        @DisplayName("존재하는 상품이면, 204 응답을 받고 논리 삭제되어 고객 조회에서 제외된다.")
        @Test
        void softDeletesProduct() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            Long productId = productJpaRepository.save(new Product(brandId, "티셔츠", new Price(1000L))).getId();

            // act
            adminDelete("/api-admin/v1/products/" + productId).andExpect(status().isNoContent());

            // assert - 레코드는 남지만 고객 조회는 404
            assertThat(productJpaRepository.findById(productId).orElseThrow().getDeletedAt()).isNotNull();
            mockMvc.perform(get("/api/v1/products/" + productId)).andExpect(status().isNotFound());
        }

        @DisplayName("삭제된 상품은 재고 변경 대상이 되지 않는다.")
        @Test
        void returnsNotFound_whenChangingStockOfDeletedProduct() throws Exception {
            // arrange
            Product product = new Product(1L, "티셔츠", new Price(1000L));
            product.delete();
            Long productId = productJpaRepository.save(product).getId();

            // act & assert
            adminPut("/api-admin/v1/products/" + productId + "/stock", "{\"quantity\":5}")
                .andExpect(status().isNotFound());
        }

        @DisplayName("이미 삭제된 상품이면, 404 응답을 받는다.")
        @Test
        void returnsNotFound_whenAlreadyDeleted() throws Exception {
            // arrange
            Product product = new Product(1L, "티셔츠", new Price(1000L));
            product.delete();
            Long productId = productJpaRepository.save(product).getId();

            // act & assert
            adminDelete("/api-admin/v1/products/" + productId).andExpect(status().isNotFound());
        }
    }

    @DisplayName("관리자 목록을 조회할 때, ")
    @Nested
    class GetListsAsAdmin {
        @DisplayName("삭제된 브랜드도 목록에 포함된다.")
        @Test
        void includesDeletedBrands() throws Exception {
            // arrange
            brandJpaRepository.save(new Brand("살아있는 브랜드"));
            Brand deleted = new Brand("삭제된 브랜드");
            deleted.delete();
            brandJpaRepository.save(deleted);

            // act & assert
            mockMvc.perform(get("/api-admin/v1/brands").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        }

        @DisplayName("삭제된 상품도 목록에 포함된다.")
        @Test
        void includesDeletedProducts() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            productJpaRepository.save(new Product(brandId, "살아있는 상품", new Price(1000L)));
            Product deleted = new Product(brandId, "삭제된 상품", new Price(1000L));
            deleted.delete();
            productJpaRepository.save(deleted);

            // act & assert
            mockMvc.perform(get("/api-admin/v1/products").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        }
    }

    /**
     * 설계 5-1 "관리자 변경 → 고객 조회" 대표 흐름을 한 테스트에서 끝까지 확인한다.
     */
    @DisplayName("관리자가 변경한 내용이 고객 조회에 반영될 때, ")
    @Nested
    class AdminChangeReflectsToCustomer {
        @DisplayName("가격을 수정하면, 고객 상세 조회에 수정된 가격이 보인다.")
        @Test
        void reflectsChangedPrice() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            Long productId = productJpaRepository.save(new Product(brandId, "티셔츠", new Price(4000L))).getId();

            // act - 관리자가 4,000원 -> 5,000원으로 수정
            adminPut("/api-admin/v1/products/" + productId, "{\"name\":\"티셔츠\",\"price\":5000}")
                .andExpect(status().isOk());

            // assert - 고객 상세에 반영
            mockMvc.perform(get("/api/v1/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(5000));
        }

        @DisplayName("재고를 0으로 변경하면, 고객 상세 조회에 재고 0으로 보인다.")
        @Test
        void reflectsChangedStock() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            Product product = new Product(brandId, "티셔츠", new Price(1000L));
            product.changeStock(10);
            Long productId = productJpaRepository.save(product).getId();

            // act
            adminPut("/api-admin/v1/products/" + productId + "/stock", "{\"quantity\":0}")
                .andExpect(status().isOk());

            // assert
            mockMvc.perform(get("/api/v1/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stockQuantity").value(0));
        }

        @DisplayName("상품을 등록하면, 고객 목록 조회에 새 상품이 나타난다.")
        @Test
        void reflectsCreatedProduct() throws Exception {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();

            // act
            adminPost("/api-admin/v1/products",
                "{\"brandId\":" + brandId + ",\"name\":\"새 상품\",\"price\":1000}")
                .andExpect(status().isCreated());

            // assert
            mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].productName").value("새 상품"));
        }
    }

    private ResultActions adminPost(String url, String body) throws Exception {
        return mockMvc.perform(post(url)
            .with(user("admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private ResultActions adminPut(String url, String body) throws Exception {
        return mockMvc.perform(put(url)
            .with(user("admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private ResultActions adminDelete(String url) throws Exception {
        return mockMvc.perform(delete(url)
            .with(user("admin").roles("ADMIN")).with(csrf()));
    }
}
