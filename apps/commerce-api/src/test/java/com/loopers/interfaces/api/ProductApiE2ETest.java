package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.infrastructure.like.ProductLikeJpaEntity;
import java.util.List;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.interfaces.api.product.ProductDto;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductApiE2ETest {
    @Autowired
    private BrandRepository brands;

    @Autowired
    private ProductRepository products;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private DatabaseCleanUp cleanUp;

    private static final String ADMIN_PRODUCTS = "/api-admin/v1/products";

    @Test
    @DisplayName("유효한 브랜드의 상품을 초기 재고 0개로 저장한다")
    void createsProduct() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        ProductDto.Create input = new ProductDto.Create(brand.getId(), "상품", 1_000L);
        var request = post(ADMIN_PRODUCTS)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isCreated()).andExpect(jsonPath("$.data.stock").value(0));
        long productId = mapper.readTree(response.andReturn().getResponse().getContentAsByteArray()).requiredAt("/data/productId").longValue();
        Product stored = products.findById(productId).orElseThrow();
        assertThat(stored.getBrandId()).isEqualTo(brand.getId());
        assertThat(stored.getStock()).isZero();
    }

    @Test
    @DisplayName("상품 정보 수정은 브랜드와 재고를 유지한다")
    void updatesInformation() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        ProductDto.Update input = new ProductDto.Update("변경", 2_000L);
        var request = put(ADMIN_PRODUCTS + "/" + product.getId())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.brandId").value(brand.getId()))
            .andExpect(jsonPath("$.data.stock").value(5));
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("변경");
        assertThat(stored.getPrice()).isEqualTo(2_000);
        assertThat(stored.getBrandId()).isEqualTo(brand.getId());
        assertThat(stored.getStock()).isEqualTo(5);
    }

    @Test
    @DisplayName("고객 상세에는 저장된 상품·브랜드·좋아요 수를 반환하고 재고는 숨긴다")
    void readsCurrentProductInformation() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        product.update("변경", 2_000);
        products.save(product);

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
    @DisplayName("재고 변경은 0부터 int 상한까지 최종 수량을 저장한다")
    void setsFinalStock(int stock) throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        ProductDto.Stock input = new ProductDto.Stock(stock);
        var request = put(ADMIN_PRODUCTS + "/" + product.getId() + "/stock")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.stock").value(stock));
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(stock);
    }

    @Test
    @DisplayName("최신순은 ID보다 생성 시각을 먼저 비교한다")
    void sortsLatestByCreationTime() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product newer = Product.create(brand.getId(), "newer", 2_000);
        newer.setStock(0);
        newer = products.save(newer);
        Product older = Product.create(brand.getId(), "older", 2_000);
        older.setStock(0);
        older = products.save(older);
        long newerId = newer.getId();
        long olderId = older.getId();
        transactions.executeWithoutResult(status -> {
            entityManager.createQuery("update ProductJpaEntity p set p.createdAt=:time where p.id=:id")
                .setParameter("time", ZonedDateTime.parse("2026-01-02T00:00:00Z"))
                .setParameter("id", newerId).executeUpdate();
            entityManager.createQuery("update ProductJpaEntity p set p.createdAt=:time where p.id=:id")
                .setParameter("time", ZonedDateTime.parse("2026-01-01T00:00:00Z"))
                .setParameter("id", olderId).executeUpdate();
        });

        // act
        var response = rest.getForEntity("/api/v1/products?sort=latest", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId")).extracting(JsonNode::longValue)
            .containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("생성 시각이 같으면 ID 역순으로 조회한다")
    void breaksLatestTiesByDescendingId() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product first = Product.create(brand.getId(), "first", 2_000);
        first.setStock(0);
        first = products.save(first);
        Product second = Product.create(brand.getId(), "second", 2_000);
        second.setStock(0);
        second = products.save(second);
        transactions.executeWithoutResult(status -> entityManager
            .createQuery("update ProductJpaEntity p set p.createdAt=:time where p.brandId=:brandId")
            .setParameter("time", ZonedDateTime.parse("2026-01-01T00:00:00Z"))
            .setParameter("brandId", brand.getId()).executeUpdate());

        // act
        var response = rest.getForEntity("/api/v1/products?sort=latest", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId")).extracting(JsonNode::longValue)
            .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("낮은 가격부터 조회하고 같은 가격이면 ID 역순으로 조회한다")
    void sortsByPriceAndDescendingId() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product expensive = Product.create(brand.getId(), "expensive", 3_000);
        expensive.setStock(0);
        expensive = products.save(expensive);
        Product cheap = Product.create(brand.getId(), "cheap", 1_000);
        cheap.setStock(0);
        cheap = products.save(cheap);
        Product newerCheap = Product.create(brand.getId(), "newerCheap", 1_000);
        newerCheap.setStock(0);
        newerCheap = products.save(newerCheap);

        // act
        var response = rest.getForEntity("/api/v1/products?sort=price_asc", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId")).extracting(JsonNode::longValue)
            .containsExactly(newerCheap.getId(), cheap.getId(), expensive.getId());
    }

    @Test
    @DisplayName("좋아요 수가 많은 순서로 조회하고 동률이면 ID 역순으로 조회한다")
    void sortsByLikesAndDescendingId() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product expensive = Product.create(brand.getId(), "expensive", 3_000);
        expensive.setStock(0);
        expensive = products.save(expensive);
        Product cheap = Product.create(brand.getId(), "cheap", 1_000);
        cheap.setStock(0);
        cheap = products.save(cheap);
        Product newerCheap = Product.create(brand.getId(), "newerCheap", 1_000);
        newerCheap.setStock(0);
        newerCheap = products.save(newerCheap);
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(1L)));
        transactions.executeWithoutResult(status -> entityManager.persist(new UserJpaEntity(2L)));
        var relations = List.of(
            new ProductLikeJpaEntity(1, expensive.getId()),
            new ProductLikeJpaEntity(2, expensive.getId()),
            new ProductLikeJpaEntity(1, cheap.getId()),
            new ProductLikeJpaEntity(1, newerCheap.getId())
        );
        transactions.executeWithoutResult(status -> relations.forEach(entityManager::persist));

        // act
        var response = rest.getForEntity("/api/v1/products?sort=likes_desc", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId")).extracting(JsonNode::longValue)
            .containsExactly(expensive.getId(), newerCheap.getId(), cheap.getId());
        assertThat(items.findValues("likeCount")).extracting(JsonNode::longValue).containsExactly(2L, 1L, 1L);
    }

    @Test
    @DisplayName("가격 정렬은 전체 상품에 적용한 뒤 페이지를 나눈다")
    void paginatesAfterSorting() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product expensive = Product.create(brand.getId(), "expensive", 3_000);
        expensive.setStock(0);
        expensive = products.save(expensive);
        Product cheap = Product.create(brand.getId(), "cheap", 1_000);
        cheap.setStock(0);
        cheap = products.save(cheap);
        Product newerCheap = Product.create(brand.getId(), "newerCheap", 1_000);
        newerCheap.setStock(0);
        newerCheap = products.save(newerCheap);

        // act
        var response = rest.getForEntity("/api/v1/products?sort=price_asc&page=1&size=1", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId")).extracting(JsonNode::longValue).containsExactly(cheap.getId());
        assertThat(response.getBody().requiredAt("/data/page").intValue()).isEqualTo(1);
        assertThat(response.getBody().requiredAt("/data/totalElements").longValue()).isEqualTo(3);
        assertThat(response.getBody().requiredAt("/data/totalPages").intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("브랜드 필터는 다른 브랜드 상품을 제외한다")
    void filtersByBrand() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        Brand otherBrand = brands.save(Brand.create("다른 브랜드"));
        Product other = Product.create(otherBrand.getId(), "other", 2_000);
        other.setStock(5);
        other = products.save(other);

        // act
        var response = rest.getForEntity("/api/v1/products?brandId=" + brand.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.findValues("productId")).extracting(JsonNode::longValue).containsExactly(product.getId());
        assertThat(response.getBody().requiredAt("/data/totalElements").longValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 브랜드 필터의 결과는 빈 배열이다")
    void returnsEmptyListForMissingBrand() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);

        // act
        var response = rest.getForEntity("/api/v1/products?brandId=999", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("상품 삭제는 행을 보존하고 삭제 상태를 저장한다")
    void softDeletesProduct() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        var request = delete(ADMIN_PRODUCTS + "/" + product.getId())
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data").doesNotExist());
        assertThat(products.findById(product.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("삭제한 상품의 고객 상세 조회는 거절한다")
    void hidesDeletedDetail() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        product.delete();
        products.save(product);

        // act
        var response = rest.getForEntity("/api/v1/products/" + product.getId(), JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().requiredAt("/meta/errorCode").asText()).isEqualTo("PRODUCT_NOT_FOUND");
    }

    @Test
    @DisplayName("삭제한 상품은 고객 목록에서 제외한다")
    void excludesDeletedProductsFromList() {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        product.delete();
        products.save(product);

        // act
        var response = rest.getForEntity("/api/v1/products", JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode items = response.getBody().requiredAt("/data/items");
        assertThat(items.isArray()).isTrue();
        assertThat(items).isEmpty();
    }

    @Test
    @DisplayName("관리자는 삭제한 상품 상세를 조회한다")
    void adminReadsDeletedProduct() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        product.delete();
        products.save(product);
        var request = get(ADMIN_PRODUCTS + "/" + product.getId())
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    @DisplayName("관리자 목록에는 삭제한 상품이 포함된다")
    void adminListsDeletedProduct() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        product.delete();
        products.save(product);
        var request = get(ADMIN_PRODUCTS)
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].deleted").value(true));
    }

    @Test
    @DisplayName("삭제한 상품의 삭제 재요청은 성공한다")
    void repeatsDeletion() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        product.delete();
        products.save(product);
        var request = delete(ADMIN_PRODUCTS + "/" + product.getId())
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isOk());
        assertThat(products.findById(product.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("삭제한 상품의 정보 변경을 거절한다")
    void rejectsUpdateAfterDeletion() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        product.delete();
        products.save(product);
        var request = put(ADMIN_PRODUCTS + "/" + product.getId())
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new ProductDto.Update("변경", 2_000L)))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.getName()).isEqualTo("product");
        assertThat(stored.getPrice()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("삭제한 상품의 재고 변경을 거절한다")
    void rejectsStockChangeAfterDeletion() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        product.delete();
        products.save(product);
        var request = put(ADMIN_PRODUCTS + "/" + product.getId() + "/stock")
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new ProductDto.Stock(1)))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.meta.errorCode").value("PRODUCT_NOT_FOUND"));
        Product stored = products.findById(product.getId()).orElseThrow();
        assertThat(stored.getStock()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\"new\"}", "{\"name\":null,\"price\":1000}", "{\"name\":\"new\",\"price\":0}",
        "{\"name\":\"new\",\"price\":null}", "{\"name\":\"new\",\"price\":1.5}"})
    @DisplayName("잘못된 수정 요청은 상품 정보를 일부만 변경하지 않는다")
    void invalidUpdatePreservesInformation(String invalidBody) throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        var request = put(ADMIN_PRODUCTS + "/" + product.getId())
            .contentType(MediaType.APPLICATION_JSON).content(invalidBody)
            .with(user("admin").roles("ADMIN")).with(csrf());

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
    @DisplayName("잘못된 재고 입력은 기존 수량을 유지한다")
    void invalidStockPreservesQuantity(String invalidValue) throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        Product product = Product.create(brand.getId(), "product", 1_000);
        product.setStock(5);
        product = products.save(product);
        String invalidBody = "{\"stock\":" + invalidValue + "}";
        var request = put(ADMIN_PRODUCTS + "/" + product.getId() + "/stock")
            .contentType(MediaType.APPLICATION_JSON).content(invalidBody)
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest());
        assertThat(products.findById(product.getId()).orElseThrow().getStock()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=101", "?sort=unknown", "?brandId=0", "?brandId=abc"})
    @DisplayName("잘못된 상품 목록 조건을 거절한다")
    void rejectsInvalidListConditions(String query) {
        // arrange
        String path = "/api/v1/products" + query;

        // act
        var response = rest.getForEntity(path, JsonNode.class);

        // assert
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("삭제된 브랜드에는 상품을 등록할 수 없다")
    void rejectsDeletedBrand() throws Exception {
        // arrange
        Brand brand = brands.save(Brand.create("브랜드"));
        brand.delete(false);
        brands.save(brand);
        ProductDto.Create input = new ProductDto.Create(brand.getId(), "상품", 1_000L);
        var request = post(ADMIN_PRODUCTS)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        assertThat(entityManager.createQuery("select count(e) from ProductJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @Test
    @DisplayName("없는 브랜드에는 상품을 등록할 수 없다")
    void rejectsMissingBrand() throws Exception {
        // arrange
        ProductDto.Create input = new ProductDto.Create(999L, "상품", 1_000L);
        var request = post(ADMIN_PRODUCTS)
            .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))
            .with(user("admin").roles("ADMIN")).with(csrf());

        // act
        var response = mvc.perform(request);

        // assert
        response.andExpect(status().isBadRequest()).andExpect(jsonPath("$.meta.errorCode").value("BRAND_NOT_FOUND"));
        assertThat(entityManager.createQuery("select count(e) from ProductJpaEntity e", Long.class)
            .getSingleResult()).isZero();
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
