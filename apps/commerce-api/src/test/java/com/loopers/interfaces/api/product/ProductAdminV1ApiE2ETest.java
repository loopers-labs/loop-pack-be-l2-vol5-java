package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
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
class ProductAdminV1ApiE2ETest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private BrandModel givenBrand() {
        return brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
    }

    @DisplayName("POST /api-admin/v1/products")
    @Nested
    class Create {
        @DisplayName("ADMIN 권한으로 유효한 브랜드를 참조해 요청하면, 201과 생성된 상품 정보를 반환한다.")
        @Test
        void createsProduct_whenRequestedByAdminWithValidBrand() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            String body = objectMapper.writeValueAsString(
                new ProductAdminV1Dto.CreateRequest("에어맥스", 129_000L, brand.getId(), 10)
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/products")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("에어맥스"))
                .andExpect(jsonPath("$.data.price").value(129_000))
                .andExpect(jsonPath("$.data.brandId").value(brand.getId()))
                .andExpect(jsonPath("$.data.remainingStock").value(10));
        }

        @DisplayName("USER 권한으로 요청하면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void throwsForbidden_whenRequestedByUser() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            String body = objectMapper.writeValueAsString(
                new ProductAdminV1Dto.CreateRequest("에어맥스", 129_000L, brand.getId(), 10)
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/products")
                    .with(user("customer").roles("USER"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isForbidden());
        }

        @DisplayName("식별 정보 없이 요청하면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void throwsForbidden_whenRequesterIsNotIdentified() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            String body = objectMapper.writeValueAsString(
                new ProductAdminV1Dto.CreateRequest("에어맥스", 129_000L, brand.getId(), 10)
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/products")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isForbidden());
        }

        @DisplayName("이름이 빈 값이면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void throwsBadRequest_whenNameIsBlank() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            String body = objectMapper.writeValueAsString(
                new ProductAdminV1Dto.CreateRequest("", 129_000L, brand.getId(), 10)
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/products")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }

        @DisplayName("가격이 음수이면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void throwsBadRequest_whenPriceIsNegative() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            String body = objectMapper.writeValueAsString(
                new ProductAdminV1Dto.CreateRequest("에어맥스", -1L, brand.getId(), 10)
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/products")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }

        @DisplayName("존재하지 않는 브랜드ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExist() throws Exception {
            // arrange
            String body = objectMapper.writeValueAsString(
                new ProductAdminV1Dto.CreateRequest("에어맥스", 129_000L, 999L, 10)
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/products")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isNotFound());
        }

        @DisplayName("삭제된 브랜드ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenBrandIsDeleted() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            brand.delete();
            brandJpaRepository.saveAndFlush(brand);
            String body = objectMapper.writeValueAsString(
                new ProductAdminV1Dto.CreateRequest("에어맥스", 129_000L, brand.getId(), 10)
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/products")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isNotFound());
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}")
    @Nested
    class Update {
        @DisplayName("ADMIN 권한으로 요청하면, 200과 수정된 상품 정보를 반환하고 브랜드ID는 유지된다.")
        @Test
        void updatesProduct_whenRequestedByAdmin() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel product = productJpaRepository.save(
                new ProductModel("에어맥스", 129_000L, brand.getId(), 10)
            );
            String body = objectMapper.writeValueAsString(
                new ProductAdminV1Dto.UpdateRequest("에어맥스 90", 139_000L)
            );

            // act & assert
            mvc.perform(put("/api-admin/v1/products/" + product.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("에어맥스 90"))
                .andExpect(jsonPath("$.data.price").value(139_000))
                .andExpect(jsonPath("$.data.brandId").value(brand.getId()));
        }

        @DisplayName("존재하지 않는 상품 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenProductDoesNotExist() throws Exception {
            // arrange
            String body = objectMapper.writeValueAsString(
                new ProductAdminV1Dto.UpdateRequest("에어맥스 90", 139_000L)
            );

            // act & assert
            mvc.perform(put("/api-admin/v1/products/999")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isNotFound());
        }
    }

    @DisplayName("DELETE /api-admin/v1/products/{productId}")
    @Nested
    class Delete {
        @DisplayName("ADMIN 권한으로 요청하면, 200을 반환하고 soft delete 된다.")
        @Test
        void deletesProduct_whenRequestedByAdmin() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel product = productJpaRepository.save(
                new ProductModel("에어맥스", 129_000L, brand.getId(), 10)
            );

            // act & assert
            mvc.perform(delete("/api-admin/v1/products/" + product.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk());
        }

        @DisplayName("존재하지 않는 상품 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenProductDoesNotExist() throws Exception {
            // act & assert
            mvc.perform(delete("/api-admin/v1/products/999")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isNotFound());
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}/stock")
    @Nested
    class ChangeStock {
        @DisplayName("ADMIN 권한으로 0 이상의 수량을 주면, 200과 변경된 재고를 반환한다.")
        @Test
        void changesStock_whenQuantityIsZeroOrPositive() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel product = productJpaRepository.save(
                new ProductModel("에어맥스", 129_000L, brand.getId(), 10)
            );
            String body = objectMapper.writeValueAsString(new ProductAdminV1Dto.StockRequest(500));

            // act & assert
            mvc.perform(put("/api-admin/v1/products/" + product.getId() + "/stock")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.remainingStock").value(500));
        }

        @DisplayName("음수 수량을 주면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void throwsBadRequest_whenQuantityIsNegative() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel product = productJpaRepository.save(
                new ProductModel("에어맥스", 129_000L, brand.getId(), 10)
            );
            String body = objectMapper.writeValueAsString(new ProductAdminV1Dto.StockRequest(-1));

            // act & assert
            mvc.perform(put("/api-admin/v1/products/" + product.getId() + "/stock")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }
    }

    @DisplayName("GET /api-admin/v1/products")
    @Nested
    class GetProducts {
        @DisplayName("ADMIN 권한으로 요청하면, 200과 삭제되지 않은 상품 목록을 반환한다.")
        @Test
        void returnsActiveProducts_whenRequestedByAdmin() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            productJpaRepository.save(new ProductModel("에어맥스", 129_000L, brand.getId(), 10));
            ProductModel deleted = productJpaRepository.save(new ProductModel("단종상품", 1000L, brand.getId(), 0));
            deleted.delete();
            productJpaRepository.saveAndFlush(deleted);

            // act & assert
            mvc.perform(get("/api-admin/v1/products")
                    .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.products.length()").value(1))
                .andExpect(jsonPath("$.data.products[0].name").value("에어맥스"));
        }
    }

    @DisplayName("GET /api-admin/v1/products/{productId}")
    @Nested
    class GetProduct {
        @DisplayName("ADMIN 권한으로 존재하는 상품 ID를 주면, 200과 상품 상세 정보를 반환한다.")
        @Test
        void returnsProduct_whenRequestedByAdminWithValidId() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel product = productJpaRepository.save(
                new ProductModel("에어맥스", 129_000L, brand.getId(), 10)
            );

            // act & assert
            mvc.perform(get("/api-admin/v1/products/" + product.getId())
                    .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("에어맥스"));
        }

        @DisplayName("삭제된 상품 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenProductIsDeleted() throws Exception {
            // arrange
            BrandModel brand = givenBrand();
            ProductModel product = productJpaRepository.save(
                new ProductModel("에어맥스", 129_000L, brand.getId(), 10)
            );
            product.delete();
            productJpaRepository.saveAndFlush(product);

            // act & assert
            mvc.perform(get("/api-admin/v1/products/" + product.getId())
                    .with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
        }
    }
}
