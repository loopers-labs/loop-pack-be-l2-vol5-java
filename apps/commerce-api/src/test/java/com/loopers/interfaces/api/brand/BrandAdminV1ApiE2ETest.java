package com.loopers.interfaces.api.brand;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.Order;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
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
class BrandAdminV1ApiE2ETest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("POST /api-admin/v1/brands")
    @Nested
    class Create {
        @DisplayName("ADMIN 권한으로 요청하면, 201과 생성된 브랜드 정보를 반환한다.")
        @Test
        void createsBrand_whenRequestedByAdmin() throws Exception {
            // arrange
            String body = objectMapper.writeValueAsString(
                new BrandAdminV1Dto.CreateRequest("나이키", "스포츠 브랜드", "신발/의류")
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/brands")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("나이키"))
                .andExpect(jsonPath("$.data.description").value("스포츠 브랜드"))
                .andExpect(jsonPath("$.data.category").value("신발/의류"));
        }

        @DisplayName("USER 권한으로 요청하면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void throwsForbidden_whenRequestedByUser() throws Exception {
            // arrange
            String body = objectMapper.writeValueAsString(
                new BrandAdminV1Dto.CreateRequest("나이키", "스포츠 브랜드", "신발/의류")
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/brands")
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
            String body = objectMapper.writeValueAsString(
                new BrandAdminV1Dto.CreateRequest("나이키", "스포츠 브랜드", "신발/의류")
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/brands")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isForbidden());
        }

        @DisplayName("이름이 빈 값이면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void throwsBadRequest_whenNameIsBlank() throws Exception {
            // arrange
            String body = objectMapper.writeValueAsString(
                new BrandAdminV1Dto.CreateRequest("", "스포츠 브랜드", "신발/의류")
            );

            // act & assert
            mvc.perform(post("/api-admin/v1/brands")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isBadRequest());
        }
    }

    @DisplayName("PUT /api-admin/v1/brands/{brandId}")
    @Nested
    class Update {
        @DisplayName("ADMIN 권한으로 요청하면, 200과 수정된 브랜드 정보를 반환한다.")
        @Test
        void updatesBrand_whenRequestedByAdmin() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );
            String body = objectMapper.writeValueAsString(
                new BrandAdminV1Dto.UpdateRequest("아디다스", "스포츠 브랜드(수정)", "신발/의류")
            );

            // act & assert
            mvc.perform(put("/api-admin/v1/brands/" + brand.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("아디다스"))
                .andExpect(jsonPath("$.data.description").value("스포츠 브랜드(수정)"));
        }

        @DisplayName("USER 권한으로 요청하면, 403 FORBIDDEN 응답을 받는다.")
        @Test
        void throwsForbidden_whenRequestedByUser() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );
            String body = objectMapper.writeValueAsString(
                new BrandAdminV1Dto.UpdateRequest("아디다스", "스포츠 브랜드(수정)", "신발/의류")
            );

            // act & assert
            mvc.perform(put("/api-admin/v1/brands/" + brand.getId())
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
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );
            String body = objectMapper.writeValueAsString(
                new BrandAdminV1Dto.UpdateRequest("아디다스", "스포츠 브랜드(수정)", "신발/의류")
            );

            // act & assert
            mvc.perform(put("/api-admin/v1/brands/" + brand.getId())
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isForbidden());
        }

        @DisplayName("존재하지 않는 브랜드 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExist() throws Exception {
            // arrange
            Long invalidId = -1L;
            String body = objectMapper.writeValueAsString(
                new BrandAdminV1Dto.UpdateRequest("아디다스", "스포츠 브랜드(수정)", "신발/의류")
            );

            // act & assert
            mvc.perform(put("/api-admin/v1/brands/" + invalidId)
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isNotFound());
        }

        @DisplayName("삭제된 브랜드 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenBrandIsDeleted() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );
            brand.delete();
            brandJpaRepository.saveAndFlush(brand);
            String body = objectMapper.writeValueAsString(
                new BrandAdminV1Dto.UpdateRequest("아디다스", "스포츠 브랜드(수정)", "신발/의류")
            );

            // act & assert
            mvc.perform(put("/api-admin/v1/brands/" + brand.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isNotFound());
        }
    }

    @DisplayName("DELETE /api-admin/v1/brands/{brandId}")
    @Nested
    class Delete {
        @DisplayName("연결된 상품이 없으면, 200을 반환하고 soft delete 된다.")
        @Test
        void deletesBrand_whenNoProductIsLinked() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );

            // act & assert
            mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk());
        }

        @DisplayName("연결된 미삭제 상품이 있으면, 재고 0인 상품까지 브랜드와 함께 삭제되고 다른 브랜드 상품은 유지된다.")
        @Test
        void deletesBrandAndLinkedProducts_includingZeroStock_andKeepsOtherBrands() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
            BrandModel otherBrand = brandJpaRepository.save(new BrandModel("아디다스", "스포츠 브랜드", "신발/의류"));
            ProductModel inStock = productJpaRepository.save(new ProductModel("에어맥스", 1000L, brand.getId(), 10));
            ProductModel soldOut = productJpaRepository.save(new ProductModel("품절상품", 1000L, brand.getId(), 0));
            ProductModel otherProduct = productJpaRepository.save(new ProductModel("울트라부스트", 1000L, otherBrand.getId(), 10));

            // act
            mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk());

            // assert — 요청 트랜잭션이 끝난 뒤 새로 읽은 DB 상태
            assertAll(
                () -> assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNotNull(),
                () -> assertThat(productJpaRepository.findById(inStock.getId()).orElseThrow().getDeletedAt()).isNotNull(),
                () -> assertThat(productJpaRepository.findById(soldOut.getId()).orElseThrow().getDeletedAt()).isNotNull(),
                () -> assertThat(brandJpaRepository.findById(otherBrand.getId()).orElseThrow().getDeletedAt()).isNull(),
                () -> assertThat(productJpaRepository.findById(otherProduct.getId()).orElseThrow().getDeletedAt()).isNull()
            );
        }

        @DisplayName("브랜드가 삭제되면, 그 브랜드의 상품은 고객 상세 조회에서 404가 된다.")
        @Test
        void linkedProductBecomesNotFoundForCustomer_afterBrandIsDeleted() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 1000L, brand.getId(), 10));

            // act
            mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk());

            // assert
            mvc.perform(get("/api/v1/products/" + product.getId()))
                .andExpect(status().isNotFound());
        }

        @DisplayName("브랜드가 삭제돼도, 그 상품이 담긴 과거 주문의 품목·단가·결제액은 그대로 조회된다.")
        @Test
        void keepsPastOrderSnapshot_afterBrandIsDeleted() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 1000L, brand.getId(), 10));
            Long userId = userJpaRepository.save(new UserModel()).getId();
            Order order = new Order(userId, List.of(new Order.OrderItemDraft(product.getId(), 2, 1000L)));
            order.confirm(2000L);
            Order savedOrder = orderJpaRepository.save(order);

            // act
            mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk());

            // assert
            mvc.perform(get("/api/v1/orders/" + savedOrder.getId()).header("X-USER-ID", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.items[0].productId").value(product.getId()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(1000))
                .andExpect(jsonPath("$.data.paidAmount").value(2000));
        }

        @DisplayName("USER 권한으로 요청하면, 403을 받고 브랜드·상품은 바뀌지 않는다.")
        @Test
        void throwsForbiddenAndChangesNothing_whenRequestedByUser() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", "스포츠 브랜드", "신발/의류"));
            ProductModel product = productJpaRepository.save(new ProductModel("에어맥스", 1000L, brand.getId(), 10));

            // act
            mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                    .with(user("customer").roles("USER"))
                    .with(csrf()))
                .andExpect(status().isForbidden());

            // assert
            assertAll(
                () -> assertThat(brandJpaRepository.findById(brand.getId()).orElseThrow().getDeletedAt()).isNull(),
                () -> assertThat(productJpaRepository.findById(product.getId()).orElseThrow().getDeletedAt()).isNull()
            );
        }

        @DisplayName("연결된 상품이 모두 삭제됐으면, 200을 반환하고 soft delete 된다.")
        @Test
        void deletesBrand_whenAllLinkedProductsAreDeleted() throws Exception {
            // arrange
            BrandModel brand = brandJpaRepository.save(
                new BrandModel("나이키", "스포츠 브랜드", "신발/의류")
            );
            ProductModel product = productJpaRepository.save(
                new ProductModel("단종상품", 1000L, brand.getId(), 0)
            );
            product.delete();
            productJpaRepository.saveAndFlush(product);

            // act & assert
            mvc.perform(delete("/api-admin/v1/brands/" + brand.getId())
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isOk());
        }

        @DisplayName("존재하지 않는 브랜드 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void throwsNotFound_whenBrandDoesNotExist() throws Exception {
            // act & assert
            mvc.perform(delete("/api-admin/v1/brands/999")
                    .with(user("admin").roles("ADMIN"))
                    .with(csrf()))
                .andExpect(status().isNotFound());
        }
    }
}
