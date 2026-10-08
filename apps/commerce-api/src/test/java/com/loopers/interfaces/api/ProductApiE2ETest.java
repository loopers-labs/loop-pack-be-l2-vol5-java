package com.loopers.interfaces.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.application.like.fixture.LikeFixture;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.infrastructure.user.fixture.UserFixture;
import com.loopers.interfaces.api.product.ProductDto;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZonedDateTime;

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiE2ETest {
    @Autowired private ProductFixture fixture;
    @Autowired private UserFixture users;

    @Autowired private LikeFixture likes;

    @Autowired private BrandFixture brands;

    @Autowired private ProductRepository products;

    @Autowired private TestRestTemplate rest;

    @Autowired private MockMvc mvc;

    @Autowired private ObjectMapper mapper;

    @Autowired private DatabaseCleanUp cleanUp;

    private static final String ADMIN_PRODUCTS = "/api-admin/v1/products";

    @Test
    void 유효한_브랜드의_상품을_초기_재고_0개로_저장한다() throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        ProductDto.Create input = new ProductDto.Create(brand.getId(), "상품", 1_000L);
        var request =
                post(ADMIN_PRODUCTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isCreated()).andExpect(jsonPath("$.data.stock").value(0));
        long productId =
                mapper.readTree(response.andReturn().getResponse().getContentAsByteArray())
                        .requiredAt("/data/productId")
                        .longValue();
        Product stored = products.findById(productId).orElseThrow();
        assertThat(stored.getBrandId()).isEqualTo(brand.getId());
        assertThat(stored.getStock()).isZero();
    }

    @Test
    void 상품_정보_수정은_브랜드와_재고를_유지한다() throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        ProductDto.Update input = new ProductDto.Update("변경", 2_000L);
        var request =
                put(ADMIN_PRODUCTS + "/" + product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.brandId").value(brand.getId()))
                .andExpect(jsonPath("$.data.stock").value(5));
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("변경");
        assertThat(stored.getPrice()).isEqualTo(2_000);
        assertThat(stored.getBrandId()).isEqualTo(brand.getId());
        assertThat(stored.getStock()).isEqualTo(5);
    }

    @Test
    void 고객_상세에는_저장된_상품_브랜드_좋아요_수를_반환하고_재고는_숨긴다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        fixture.updateProduct(product.getId(), "변경", 2_000);

        // act
        var response = rest.getForEntity("/api/v1/products/" + product.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode data = response.getBody().requiredAt("/data");
        assertThat(data.required("name").asText()).isEqualTo("변경");
        assertThat(data.required("price").longValue()).isEqualTo(2_000);
        assertThat(data.requiredAt("/brand/brandId").longValue()).isEqualTo(brand.getId());
        assertThat(data.required("likeCount").isIntegralNumber()).isTrue();
        assertThat(data.required("likeCount").longValue()).isZero();
        assertThat(data.has("stock")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, Integer.MAX_VALUE})
    void 재고_변경은_0부터_int_상한까지_최종_수량을_저장한다(int stock) throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        ProductDto.Stock input = new ProductDto.Stock(stock);
        var request =
                put(ADMIN_PRODUCTS + "/" + product.getId() + "/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.stock").value(stock));
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(stock);
    }

    @Test
    void 최신순은_ID보다_생성_시각을_먼저_비교한다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product newer = fixture.createProduct(brand.getId(), "newer", 2_000, 0);
        Product older = fixture.createProduct(brand.getId(), "older", 2_000, 0);
        long newerId = newer.getId();
        long olderId = older.getId();
        fixture.createdAt(newerId, ZonedDateTime.parse("2026-01-02T00:00:00Z"));
        fixture.createdAt(olderId, ZonedDateTime.parse("2026-01-01T00:00:00Z"));

        // act
        var response = rest.getForEntity("/api/v1/products?sort=latest", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId"))
                .extracting(JsonNode::longValue)
                .containsExactly(newer.getId(), older.getId());
    }

    @Test
    void 생성_시각이_같으면_ID_역순으로_조회한다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product first = fixture.createProduct(brand.getId(), "first", 2_000, 0);
        Product second = fixture.createProduct(brand.getId(), "second", 2_000, 0);
        fixture.createdAtForBrand(brand.getId(), ZonedDateTime.parse("2026-01-01T00:00:00Z"));

        // act
        var response = rest.getForEntity("/api/v1/products?sort=latest", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId"))
                .extracting(JsonNode::longValue)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void 낮은_가격부터_조회하고_같은_가격이면_ID_역순으로_조회한다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product expensive = fixture.createProduct(brand.getId(), "expensive", 3_000, 0);
        Product cheap = fixture.createProduct(brand.getId(), "cheap", 1_000, 0);
        Product newerCheap = fixture.createProduct(brand.getId(), "newerCheap", 1_000, 0);

        // act
        var response = rest.getForEntity("/api/v1/products?sort=price_asc", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId"))
                .extracting(JsonNode::longValue)
                .containsExactly(newerCheap.getId(), cheap.getId(), expensive.getId());
    }

    @Test
    void 좋아요_수가_많은_순서로_조회하고_동률이면_ID_역순으로_조회한다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product expensive = fixture.createProduct(brand.getId(), "expensive", 3_000, 0);
        Product cheap = fixture.createProduct(brand.getId(), "cheap", 1_000, 0);
        Product newerCheap = fixture.createProduct(brand.getId(), "newerCheap", 1_000, 0);
        users.createUser(1);
        users.createUser(2);
        likes.createLike(1, expensive.getId());
        likes.createLike(2, expensive.getId());
        likes.createLike(1, cheap.getId());
        likes.createLike(1, newerCheap.getId());

        // act
        var response = rest.getForEntity("/api/v1/products?sort=likes_desc", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId"))
                .extracting(JsonNode::longValue)
                .containsExactly(expensive.getId(), newerCheap.getId(), cheap.getId());
        assertThat(items.findValues("likeCount"))
                .extracting(JsonNode::longValue)
                .containsExactly(2L, 1L, 1L);
    }

    @Test
    void 가격_정렬은_전체_상품에_적용한_뒤_페이지를_나눈다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product expensive = fixture.createProduct(brand.getId(), "expensive", 3_000, 0);
        Product cheap = fixture.createProduct(brand.getId(), "cheap", 1_000, 0);
        Product newerCheap = fixture.createProduct(brand.getId(), "newerCheap", 1_000, 0);

        // act
        var response =
                rest.getForEntity("/api/v1/products?sort=price_asc&page=1&size=1", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId"))
                .extracting(JsonNode::longValue)
                .containsExactly(cheap.getId());
        assertThat(response.getBody().requiredAt("/data/page").intValue()).isEqualTo(1);
        assertThat(response.getBody().requiredAt("/data/totalElements").longValue()).isEqualTo(3);
        assertThat(response.getBody().requiredAt("/data/totalPages").intValue()).isEqualTo(3);
    }

    @Test
    void 브랜드_필터는_다른_브랜드_상품을_제외한다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        Brand otherBrand = brands.createBrand("다른 브랜드");
        Product other = fixture.createProduct(otherBrand.getId(), "other", 2_000, 5);

        // act
        var response =
                rest.getForEntity("/api/v1/products?brandId=" + brand.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId"))
                .extracting(JsonNode::longValue)
                .containsExactly(product.getId());
        assertThat(response.getBody().requiredAt("/data/totalElements").longValue()).isEqualTo(1);
    }

    @Test
    void 없는_브랜드_필터의_결과는_빈_배열이다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);

        // act
        var response = rest.getForEntity("/api/v1/products?brandId=999", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).isEmpty();
    }

    @Test
    void 상품_삭제는_행을_보존하고_삭제_상태를_저장한다() throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        var request =
                delete(ADMIN_PRODUCTS + "/" + product.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(products.findById(product.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void 삭제한_상품의_고객_상세_조회는_거절한다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        fixture.deleteProduct(product.getId());

        // act
        var response = rest.getForEntity("/api/v1/products/" + product.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    void 삭제한_상품은_고객_목록에서_제외한다() {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        fixture.deleteProduct(product.getId());

        // act
        var response = rest.getForEntity("/api/v1/products", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).isEmpty();
    }

    @Test
    void 관리자는_삭제한_상품_상세를_조회한다() throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        fixture.deleteProduct(product.getId());
        var request =
                get(ADMIN_PRODUCTS + "/" + product.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void 관리자_목록에는_삭제한_상품이_포함된다() throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        fixture.deleteProduct(product.getId());
        var request = get(ADMIN_PRODUCTS).with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].deleted").value(true));
    }

    @Test
    void 삭제한_상품의_삭제_재요청은_성공한다() throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        fixture.deleteProduct(product.getId());
        var request =
                delete(ADMIN_PRODUCTS + "/" + product.getId())
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk());
        assertThat(products.findById(product.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void 삭제한_상품의_정보_변경을_거절한다() throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        fixture.deleteProduct(product.getId());
        var request =
                put(ADMIN_PRODUCTS + "/" + product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ProductDto.Update("변경", 2_000L)))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("product");
        assertThat(stored.getPrice()).isEqualTo(1_000);
    }

    @Test
    void 삭제한_상품의_재고_변경을_거절한다() throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        fixture.deleteProduct(product.getId());
        var request =
                put(ADMIN_PRODUCTS + "/" + product.getId() + "/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new ProductDto.Stock(1)))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.getStock()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"name\":\"new\"}",
                "{\"name\":null,\"price\":1000}",
                "{\"name\":\"new\",\"price\":0}",
                "{\"name\":\"new\",\"price\":null}",
                "{\"name\":\"new\",\"price\":1.5}"
            })
    void 잘못된_수정_요청은_상품_정보를_일부만_변경하지_않는다(String invalidBody) throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        var request =
                put(ADMIN_PRODUCTS + "/" + product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest());
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("product");
        assertThat(stored.getPrice()).isEqualTo(1_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "2147483648", "1.5", "null", "\"2\""})
    void 잘못된_재고_입력은_기존_수량을_유지한다(String invalidValue) throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        Product product = fixture.createProduct(brand.getId(), "product", 1_000, 5);
        String invalidBody = "{\"stock\":" + invalidValue + "}";
        var request =
                put(ADMIN_PRODUCTS + "/" + product.getId() + "/stock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody)
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest());
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "?page=-1",
                "?size=0",
                "?size=101",
                "?sort=unknown",
                "?brandId=0",
                "?brandId=abc"
            })
    void 잘못된_상품_목록_조건을_거절한다(String query) {
        // arrange
        String path = "/api/v1/products" + query;

        // act
        var response = rest.getForEntity(path, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void 삭제된_브랜드에는_상품을_등록할_수_없다() throws Exception {
        // arrange
        Brand brand = brands.createBrand("브랜드");
        brands.deleteBrand(brand.getId());
        ProductDto.Create input = new ProductDto.Create(brand.getId(), "상품", 1_000L);
        var request =
                post(ADMIN_PRODUCTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        assertThat(fixture.rowCount()).isZero();
    }

    @Test
    void 없는_브랜드에는_상품을_등록할_수_없다() throws Exception {
        // arrange
        ProductDto.Create input = new ProductDto.Create(999L, "상품", 1_000L);
        var request =
                post(ADMIN_PRODUCTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input))
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        assertThat(fixture.rowCount()).isZero();
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
