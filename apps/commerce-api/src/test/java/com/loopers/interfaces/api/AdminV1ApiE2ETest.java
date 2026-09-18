package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminV1ApiE2ETest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("관리자 브랜드 API는, ")
    @Nested
    class Brands {
        @DisplayName("관리자가 요청하면, 브랜드를 생성·수정·삭제할 수 있다.")
        @Test
        void allowsCrud_whenRequestedByAdmin() throws Exception {
            // create
            String createBody = objectMapper.writeValueAsString(Map.of("name", "나이키"));
            String createResponse = mvc.perform(post("/api-admin/v1/brands")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("나이키"))
                .andReturn().getResponse().getContentAsString();

            Long brandId = objectMapper.readTree(createResponse).path("data").path("id").asLong();

            // update
            String updateBody = objectMapper.writeValueAsString(Map.of("name", "아디다스"));
            mvc.perform(put("/api-admin/v1/brands/" + brandId)
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("아디다스"));

            // delete
            mvc.perform(delete("/api-admin/v1/brands/" + brandId)
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk());

            BrandModel deleted = brandJpaRepository.findById(brandId).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
        }

        @DisplayName("일반 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByNonAdminUser() throws Exception {
            mvc.perform(get("/api-admin/v1/brands").with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        }

        @DisplayName("식별되지 않은 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByUnauthenticatedUser() throws Exception {
            mvc.perform(get("/api-admin/v1/brands"))
                .andExpect(status().isForbidden());
        }

        @DisplayName("연결된 삭제되지 않은 상품이 있으면, 삭제 요청 시 409를 응답한다.")
        @Test
        void returns409_whenBrandHasActiveProduct() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));
            productJpaRepository.save(new ProductModel(brand.getId(), "runner", 10_000L, 5));

            // act, assert
            mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isConflict());
        }
    }

    @DisplayName("관리자 상품 API는, ")
    @Nested
    class Products {
        @DisplayName("관리자가 요청하면, 상품을 생성·수정·재고변경·삭제할 수 있다.")
        @Test
        void allowsCrud_whenRequestedByAdmin() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키"));

            // create
            String createBody = objectMapper.writeValueAsString(
                Map.of("brandId", brand.getId(), "name", "runner", "price", 10_000, "stock", 5));
            String createResponse = mvc.perform(post("/api-admin/v1/products")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("runner"))
                .andReturn().getResponse().getContentAsString();

            Long productId = objectMapper.readTree(createResponse).path("data").path("id").asLong();

            // update
            String updateBody = objectMapper.writeValueAsString(Map.of("name", "runner-pro", "price", 20_000));
            mvc.perform(put("/api-admin/v1/products/" + productId)
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("runner-pro"));

            // change stock
            String stockBody = objectMapper.writeValueAsString(Map.of("stock", 100));
            mvc.perform(patch("/api-admin/v1/products/" + productId + "/stock")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(stockBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stock").value(100));

            // delete
            mvc.perform(delete("/api-admin/v1/products/" + productId)
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk());

            ProductModel deleted = productJpaRepository.findById(productId).orElseThrow();
            assertThat(deleted.getDeletedAt()).isNotNull();
        }

        @DisplayName("존재하지 않는 브랜드로 생성을 요청하면, 404를 응답한다.")
        @Test
        void returns404_whenBrandDoesNotExist() throws Exception {
            // arrange
            String createBody = objectMapper.writeValueAsString(
                Map.of("brandId", 999L, "name", "runner", "price", 10_000, "stock", 5));

            // act, assert
            mvc.perform(post("/api-admin/v1/products")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
                .andExpect(status().isNotFound());
        }

        @DisplayName("일반 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByNonAdminUser() throws Exception {
            mvc.perform(get("/api-admin/v1/products").with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        }

        @DisplayName("식별되지 않은 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByUnauthenticatedUser() throws Exception {
            mvc.perform(get("/api-admin/v1/products"))
                .andExpect(status().isForbidden());
        }
    }

    @DisplayName("관리자 주문 API는, ")
    @Nested
    class Orders {
        @DisplayName("관리자가 요청하면, 구매자와 무관하게 주문 목록과 상세를 조회할 수 있다.")
        @Test
        void allowsViewingAnyOrder_whenRequestedByAdmin() throws Exception {
            // arrange
            OrderModel order = orderJpaRepository.save(new OrderModel(1L, List.of(new OrderItem(1L, 2, 10_000L))));

            // act, assert
            mvc.perform(get("/api-admin/v1/orders").with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(1));

            mvc.perform(get("/api-admin/v1/orders/" + order.getId()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalAmount").value(20_000));
        }

        @DisplayName("일반 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByNonAdminUser() throws Exception {
            mvc.perform(get("/api-admin/v1/orders").with(user("customer").roles("USER")))
                .andExpect(status().isForbidden());
        }

        @DisplayName("식별되지 않은 사용자가 요청하면, 403을 응답한다.")
        @Test
        void returns403_whenRequestedByUnauthenticatedUser() throws Exception {
            mvc.perform(get("/api-admin/v1/orders"))
                .andExpect(status().isForbidden());
        }
    }
}
