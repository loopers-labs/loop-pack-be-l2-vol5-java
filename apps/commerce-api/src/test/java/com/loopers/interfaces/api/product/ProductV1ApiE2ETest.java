package com.loopers.interfaces.api.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.admin.product.AdminProductV1Dto;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ProductV1ApiE2ETest {

    private static final String ENDPOINT_GET_LIST = "/api/v1/products";
    private static final Function<Long, String> ENDPOINT_GET = id -> "/api/v1/products/" + id;
    private static final Function<Long, String> ENDPOINT_PUT_STOCK = id -> "/api-admin/v1/products/" + id + "/stock";

    private final TestRestTemplate testRestTemplate;
    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final BrandJpaRepository brandJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final LikeJpaRepository likeJpaRepository;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public ProductV1ApiE2ETest(
        TestRestTemplate testRestTemplate,
        MockMvc mockMvc,
        ObjectMapper objectMapper,
        BrandJpaRepository brandJpaRepository,
        ProductJpaRepository productJpaRepository,
        LikeJpaRepository likeJpaRepository,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.testRestTemplate = testRestTemplate;
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.brandJpaRepository = brandJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.likeJpaRepository = likeJpaRepository;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("GET " + ENDPOINT_GET_LIST)
    @Nested
    class GetProducts {
        @DisplayName("정상 요청이면, 200과 브랜드 정보·좋아요 수가 포함된 상품 목록을 반환한다.")
        @Test
        void returnsProductList_whenRequestIsValid() {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            Long productId = productJpaRepository.save(new Product(brandId, "루퍼스 티셔츠", new Price(1000L))).getId();
            likeJpaRepository.save(new Like(1L, productId));

            // act
            ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> response = getList(ENDPOINT_GET_LIST);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data()).hasSize(1);
            assertThat(response.getBody().data().get(0).brandName()).isEqualTo("루퍼스");
            assertThat(response.getBody().data().get(0).likeCount()).isEqualTo(1L);
        }

        @DisplayName("지원하지 않는 정렬값을 주면, 400 BAD_REQUEST 응답을 받는다.")
        @Test
        void returnsBadRequest_whenSortIsNotSupported() {
            // act
            ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> response =
                getList(ENDPOINT_GET_LIST + "?sort=price_desc");

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @DisplayName("삭제된 상품은 목록 응답에서 제외된다.")
        @Test
        void excludesDeletedProducts() {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            productJpaRepository.save(new Product(brandId, "살아있는 상품", new Price(1000L)));
            Product target = new Product(brandId, "삭제된 상품", new Price(1000L));
            target.delete();
            productJpaRepository.save(target);

            // act
            ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> response = getList(ENDPOINT_GET_LIST);

            // assert
            assertThat(response.getBody().data()).hasSize(1);
            assertThat(response.getBody().data().get(0).productName()).isEqualTo("살아있는 상품");
        }

        @DisplayName("브랜드로 필터하면, 해당 브랜드의 상품만 반환한다.")
        @Test
        void filtersByBrandId() {
            // arrange
            Long targetBrandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            Long otherBrandId = brandJpaRepository.save(new Brand("다른 브랜드")).getId();
            productJpaRepository.save(new Product(targetBrandId, "루퍼스 상품", new Price(1000L)));
            productJpaRepository.save(new Product(otherBrandId, "다른 브랜드 상품", new Price(1000L)));

            // act
            ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> response =
                getList(ENDPOINT_GET_LIST + "?brandId=" + targetBrandId);

            // assert
            assertThat(response.getBody().data()).hasSize(1);
            assertThat(response.getBody().data().get(0).productName()).isEqualTo("루퍼스 상품");
        }
    }

    @DisplayName("GET /api/v1/products/{productId}")
    @Nested
    class GetProduct {
        @DisplayName("존재하는 상품 ID를 주면, 200과 상품 상세를 반환한다.")
        @Test
        void returnsProductDetail_whenProductExists() {
            // arrange
            Long brandId = brandJpaRepository.save(new Brand("루퍼스")).getId();
            Long productId = productJpaRepository.save(new Product(brandId, "루퍼스 티셔츠", new Price(1000L))).getId();

            // act
            ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> response = get(productId);

            // assert
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().data().productName()).isEqualTo("루퍼스 티셔츠");
            assertThat(response.getBody().data().brandName()).isEqualTo("루퍼스");
            assertThat(response.getBody().data().likeCount()).isZero();
        }

        @DisplayName("없거나 삭제된 상품 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsNotFound_whenProductIsAbsentOrDeleted() {
            // arrange
            Product target = new Product(1L, "삭제된 상품", new Price(1000L));
            target.delete();
            Long deletedId = productJpaRepository.save(target).getId();

            // assert
            assertThat(get(-1L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get(deletedId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @DisplayName("PUT /api-admin/v1/products/{productId}/stock")
    @Nested
    class ChangeStock {
        @DisplayName("0 이상 수량을 주면, 200과 해당 값으로 설정된 재고를 반환한다.")
        @Test
        void returnsChangedStock_whenQuantityIsNotNegative() throws Exception {
            // arrange
            Long productId = productJpaRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L))).getId();

            // act
            changeStock(productId, 7).andExpect(status().isOk());

            // assert
            Product found = productJpaRepository.findById(productId).orElseThrow();
            assertThat(found.getStock().getQuantity()).isEqualTo(7);
        }

        @DisplayName("음수 수량을 주면, 400 BAD_REQUEST 응답을 받고 기존 재고가 유지된다.")
        @Test
        void keepsStock_whenQuantityIsNegative() throws Exception {
            // arrange
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));
            product.changeStock(5);
            Long productId = productJpaRepository.save(product).getId();

            // act
            changeStock(productId, -1).andExpect(status().isBadRequest());

            // assert
            Product found = productJpaRepository.findById(productId).orElseThrow();
            assertThat(found.getStock().getQuantity()).isEqualTo(5);
        }

        @DisplayName("존재하지 않는 상품 ID를 주면, 404 NOT_FOUND 응답을 받는다.")
        @Test
        void returnsNotFound_whenProductIsAbsent() throws Exception {
            // act & assert
            changeStock(-1L, 7).andExpect(status().isNotFound());
        }

        private org.springframework.test.web.servlet.ResultActions changeStock(Long productId, int quantity)
            throws Exception {
            String body = objectMapper.writeValueAsString(new AdminProductV1Dto.ChangeStockRequest(quantity));
            return mockMvc.perform(put(ENDPOINT_PUT_STOCK.apply(productId))
                .with(user("admin").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
        }
    }

    private ResponseEntity<ApiResponse<List<ProductV1Dto.ProductResponse>>> getList(String url) {
        ParameterizedTypeReference<ApiResponse<List<ProductV1Dto.ProductResponse>>> responseType =
            new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(null), responseType);
    }

    private ResponseEntity<ApiResponse<ProductV1Dto.ProductResponse>> get(Long productId) {
        ParameterizedTypeReference<ApiResponse<ProductV1Dto.ProductResponse>> responseType =
            new ParameterizedTypeReference<>() {};
        return testRestTemplate.exchange(
            ENDPOINT_GET.apply(productId), HttpMethod.GET, new HttpEntity<>(null), responseType);
    }
}
