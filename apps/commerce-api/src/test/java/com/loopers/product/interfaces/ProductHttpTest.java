package com.loopers.product.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.brand.domain.Brand;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Set;

import static com.loopers.support.http.ApiHttp.admin;
import static com.loopers.support.http.ApiHttp.body;
import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.data;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.fieldNames;
import static com.loopers.support.http.ApiHttp.findById;
import static com.loopers.support.http.ApiHttp.ids;
import static com.loopers.support.http.ApiHttp.isNullOrAbsent;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class ProductHttpTest {

    private static final String CUSTOMER_PRODUCTS = "/api/v1/products";
    private static final String CUSTOMER_PRODUCT = "/api/v1/products/{productId}";
    private static final String CUSTOMER_BRAND = "/api/v1/brands/{brandId}";
    private static final String ADMIN_PRODUCTS = "/api-admin/v1/products";
    private static final String ADMIN_PRODUCT = "/api-admin/v1/products/{productId}";
    private static final String ADMIN_STOCK = "/api-admin/v1/products/{productId}/stock";
    private static final String ADMIN_BRAND = "/api-admin/v1/brands/{brandId}";
    private static final long MISSING_ID = 999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @DisplayName("[R-CATALOG-02] 고객은 상품 목록과 상품 상세를 조회할 수 있다.")
    @Nested
    class CustomerProductListAndDetail {

        @DisplayName("[동등 클래스 분할] 삭제되지 않은 상품은 고객 목록에 담기고, 상세 조회는 200이며 id·name·price를 준다.")
        @Test
        void readsListAndDetail() throws Exception {
            // arrange
            User customer = fixture.user();
            Brand brand = fixture.brand("Nike");
            Product product = fixture.product(brand, "Air", 3_000L, 5);

            // act
            MvcResult list = mockMvc.perform(get(CUSTOMER_PRODUCTS).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode item = findById(data(list).path("content"), product.getId());
            assertAll(
                () -> assertThat(ids(data(list).path("content"))).containsExactly(product.getId()),
                () -> assertThat(item.path("name").asText()).isEqualTo("Air"),
                () -> assertThat(item.path("price").asLong()).isEqualTo(3_000L)
            );
            mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.id").value(product.getId()))
                .andExpect(jsonPath("$.data.name").value("Air"))
                .andExpect(jsonPath("$.data.price").value(3_000L));
        }
    }

    @DisplayName("[R-CATALOG-03] 상품 목록과 상세에는 브랜드 정보와 좋아요 수가 포함된다.")
    @Nested
    class BrandAndLikeCountInCustomerProduct {

        @DisplayName("[동등 클래스 분할] 좋아요가 2개인 상품의 목록 항목과 상세에는 브랜드 id·name과 likeCount 2가 있다.")
        @Test
        void includesBrandAndLikeCount() throws Exception {
            // arrange
            User customer = fixture.user();
            Brand brand = fixture.brand("Nike");
            Product product = fixture.product(brand, "Air", 3_000L, 5);
            fixture.like(fixture.user(), product);
            fixture.like(fixture.user(), product);

            // act
            MvcResult list = mockMvc.perform(get(CUSTOMER_PRODUCTS).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult detail = mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode item = findById(data(list).path("content"), product.getId());
            assertAll(
                () -> assertThat(item.path("brand").path("id").asLong()).isEqualTo(brand.getId()),
                () -> assertThat(item.path("brand").path("name").asText()).isEqualTo("Nike"),
                () -> assertThat(item.path("likeCount").asLong(-1)).isEqualTo(2L),
                () -> assertThat(data(detail).path("brand").path("id").asLong()).isEqualTo(brand.getId()),
                () -> assertThat(data(detail).path("brand").path("name").asText()).isEqualTo("Nike"),
                () -> assertThat(data(detail).path("likeCount").asLong(-1)).isEqualTo(2L)
            );
        }

        @DisplayName("[경계값 분석] 좋아요가 없는 상품의 likeCount는 0이다.")
        @Test
        void showsZeroLikeCount() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act & assert
            mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.likeCount").value(0));
        }
    }

    @DisplayName("[R-CATALOG-07] 존재하지 않거나 삭제된 브랜드·상품의 고객 상세 조회는 없는 대상 오류로 처리한다.")
    @Nested
    class CustomerProductNotFound {

        @DisplayName("[동등 클래스 분할] 존재하지 않는 상품을 조회하면 404 PRODUCT_NOT_FOUND이다.")
        @Test
        void rejectsMissingProduct() throws Exception {
            // arrange
            User customer = fixture.user();

            // act & assert
            mockMvc.perform(get(CUSTOMER_PRODUCT, MISSING_ID).with(customer(customer)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
        }

        @DisplayName("[동등 클래스 분할] 삭제된 상품을 조회하면 404 PRODUCT_NOT_FOUND이다.")
        @Test
        void rejectsDeletedProduct() throws Exception {
            // arrange
            User customer = fixture.user();
            Product deleted = fixture.deletedProduct(fixture.brand("Nike"), "Air", 3_000L);

            // act & assert
            mockMvc.perform(get(CUSTOMER_PRODUCT, deleted.getId()).with(customer(customer)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
        }
    }

    @DisplayName("[R-CATALOG-08] 상품 목록 조건(브랜드 필터, 페이지, 정렬)의 잘못된 입력을 처리한다.")
    @Nested
    class RejectMalformedListCondition {

        @DisplayName("[오류 추측] 숫자가 아닌 brandId·page·size로 목록을 조회하면 400 INVALID_REQUEST이다.")
        @ParameterizedTest(name = "{0}={1}")
        @CsvSource({"brandId, abc", "page, abc", "size, abc"})
        void rejectsNonNumericCondition(String name, String value) throws Exception {
            // arrange
            User customer = fixture.user();
            fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act & assert
            mockMvc.perform(get(CUSTOMER_PRODUCTS).param(name, value).with(customer(customer)))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }
    }

    @DisplayName("[P-CATALOG-01] 고객에게 재고 수량은 보여 주지 않고 품절 여부만 보여 준다.")
    @Nested
    class SoldOutInsteadOfQuantity {

        @DisplayName("[경계값 분석] 재고 0인 상품은 soldOut이 true, 재고 1인 상품은 false로 목록과 상세에 보인다.")
        @ParameterizedTest(name = "재고 {0} → soldOut {1}")
        @CsvSource({"0, true", "1, false"})
        void showsSoldOut(int stock, boolean soldOut) throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, stock);

            // act
            MvcResult list = mockMvc.perform(get(CUSTOMER_PRODUCTS).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult detail = mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode item = findById(data(list).path("content"), product.getId());
            assertAll(
                () -> assertThat(item.path("soldOut").isBoolean()).isTrue(),
                () -> assertThat(item.path("soldOut").asBoolean()).isEqualTo(soldOut),
                () -> assertThat(data(detail).path("soldOut").isBoolean()).isTrue(),
                () -> assertThat(data(detail).path("soldOut").asBoolean()).isEqualTo(soldOut)
            );
        }

        @DisplayName("[동등 클래스 분할] 재고 7인 상품의 고객 목록 항목과 상세에는 재고 수량 필드가 없다.")
        @Test
        void hidesStockQuantity() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 7);

            // act
            MvcResult list = mockMvc.perform(get(CUSTOMER_PRODUCTS).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult detail = mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode item = findById(data(list).path("content"), product.getId());
            assertAll(
                () -> assertThat(item.isMissingNode()).isFalse(),
                () -> assertThat(item.has("stock")).isFalse(),
                () -> assertThat(data(detail).isObject()).isTrue(),
                () -> assertThat(data(detail).has("stock")).isFalse()
            );
        }
    }

    @DisplayName("[P-CATALOG-05] 상품 목록의 기본 페이지 크기는 20개, 최대 크기는 100개다.")
    @Nested
    class DefaultAndMaxPageSize {

        @DisplayName("[경계값 분석] size 없이 21개 상품을 조회하면 20개를 주고 size는 20, totalElements는 21이다.")
        @Test
        void usesDefaultSize() throws Exception {
            // arrange
            User customer = fixture.user();
            Brand brand = fixture.brand("Nike");
            for (int index = 0; index < 21; index++) {
                fixture.product(brand, "Product " + index, 1_000L, 1);
            }

            // act & assert
            mockMvc.perform(get(CUSTOMER_PRODUCTS).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.content.length()").value(20))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(21));
        }

        @DisplayName("[경계값 분석] size 100은 200이고 size 100으로 응답한다.")
        @Test
        void acceptsMaxSize() throws Exception {
            // arrange
            User customer = fixture.user();
            fixture.product(fixture.brand("Nike"), "Air", 1_000L, 1);

            // act & assert
            mockMvc.perform(get(CUSTOMER_PRODUCTS).param("size", "100").with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.size").value(100));
        }

        @DisplayName("[경계값 분석] size 101은 400 INVALID_REQUEST이다.")
        @Test
        void rejectsSizeOverMax() throws Exception {
            // arrange
            User customer = fixture.user();

            // act & assert
            mockMvc.perform(get(CUSTOMER_PRODUCTS).param("size", "101").with(customer(customer)))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }
    }

    @DisplayName("[P-CATALOG-06] 지원하지 않는 정렬값이나 잘못된 페이지 조건은 기본값으로 처리하지 않고 오류로 거절한다.")
    @Nested
    class RejectInsteadOfDefault {

        @DisplayName("[동등 클래스 분할] 지원하는 정렬값은 기본값이 아니라 요청한 정렬로 첫 상품을 정한다.")
        @ParameterizedTest(name = "sort={0} → {1}")
        @CsvSource({"latest, LATEST", "price_asc, CHEAPEST", "likes_desc, MOST_LIKED"})
        void appliesSupportedSort(String sort, String expectedFirst) throws Exception {
            // arrange
            User customer = fixture.user();
            Brand brand = fixture.brand("Nike");
            Product cheapest = fixture.product(brand, "Cheapest", 1_000L, 1);
            Product mostLiked = fixture.product(brand, "MostLiked", 3_000L, 1);
            Product latest = fixture.product(brand, "Latest", 2_000L, 1);
            fixture.like(fixture.user(), mostLiked);
            fixture.like(fixture.user(), mostLiked);
            Long expectedId = switch (expectedFirst) {
                case "LATEST" -> latest.getId();
                case "CHEAPEST" -> cheapest.getId();
                default -> mostLiked.getId();
            };

            // act
            MvcResult result = mockMvc.perform(get(CUSTOMER_PRODUCTS).param("sort", sort).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertThat(ids(data(result).path("content"))).first().isEqualTo(expectedId);
        }

        @DisplayName("[동등 클래스 분할] 지원하지 않는 정렬값, 음수 page, 0인 size는 400 INVALID_REQUEST이다.")
        @ParameterizedTest(name = "{0}={1}")
        @CsvSource({"sort, popular", "sort, price_desc", "page, -1", "size, 0"})
        void rejectsUnsupportedCondition(String name, String value) throws Exception {
            // arrange
            User customer = fixture.user();
            fixture.product(fixture.brand("Nike"), "Air", 1_000L, 1);

            // act & assert
            mockMvc.perform(get(CUSTOMER_PRODUCTS).param(name, value).with(customer(customer)))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }
    }

    @DisplayName("[P-CATALOG-07] 마지막 페이지를 넘는 페이지를 요청하면 빈 목록을 제공한다.")
    @Nested
    class EmptyPageBeyondLast {

        @DisplayName("[경계값 분석] 상품 3개를 size 2로 조회하면 page 1은 1개, page 2는 빈 목록이고 totalElements는 3이다.")
        @Test
        void returnsEmptyBeyondLastPage() throws Exception {
            // arrange
            User customer = fixture.user();
            Brand brand = fixture.brand("Nike");
            fixture.product(brand, "First", 1_000L, 1);
            fixture.product(brand, "Second", 1_000L, 1);
            fixture.product(brand, "Third", 1_000L, 1);

            // act & assert
            mockMvc.perform(get(CUSTOMER_PRODUCTS).param("page", "1").param("size", "2").with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(3));
            mockMvc.perform(get(CUSTOMER_PRODUCTS).param("page", "2").param("size", "2").with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        }
    }

    @DisplayName("[R-ACCESS-06] 고객과 관리자에게 제공하는 응답 정보를 구분한다.")
    @Nested
    class SeparateCustomerAndAdminFields {

        @DisplayName("[동등 클래스 분할] 같은 상품을 고객은 id·name·price·brand·likeCount·soldOut으로, 관리자는 id·name·price·brand·stock·deleted로 받는다.")
        @Test
        void separatesFields() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act
            MvcResult customerDetail = mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId())
                    .with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult adminDetail = mockMvc.perform(get(ADMIN_PRODUCT, product.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(fieldNames(data(customerDetail)))
                    .isEqualTo(Set.of("id", "name", "price", "brand", "likeCount", "soldOut")),
                () -> assertThat(fieldNames(data(customerDetail).path("brand"))).isEqualTo(Set.of("id", "name")),
                () -> assertThat(fieldNames(data(adminDetail)))
                    .isEqualTo(Set.of("id", "name", "price", "brand", "stock", "deleted")),
                () -> assertThat(fieldNames(data(adminDetail).path("brand"))).isEqualTo(Set.of("id", "name"))
            );
        }
    }

    @DisplayName("[P-ADMIN-09] 관리자에게 보여 주는 상품 정보에는 브랜드 정보를 포함하고 좋아요 수는 포함하지 않는다.")
    @Nested
    class BrandWithoutLikeCountForAdmin {

        @DisplayName("[동등 클래스 분할] 좋아요가 있는 상품도 관리자 목록 항목과 상세에는 브랜드 id·name이 있고 likeCount가 없다.")
        @Test
        void includesBrandWithoutLikeCount() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");
            Product product = fixture.product(brand, "Air", 3_000L, 5);
            fixture.like(fixture.user(), product);

            // act
            MvcResult list = mockMvc.perform(get(ADMIN_PRODUCTS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult detail = mockMvc.perform(get(ADMIN_PRODUCT, product.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode item = findById(data(list).path("content"), product.getId());
            assertAll(
                () -> assertThat(item.path("brand").path("id").asLong()).isEqualTo(brand.getId()),
                () -> assertThat(item.path("brand").path("name").asText()).isEqualTo("Nike"),
                () -> assertThat(item.has("likeCount")).isFalse(),
                () -> assertThat(data(detail).path("brand").path("id").asLong()).isEqualTo(brand.getId()),
                () -> assertThat(data(detail).path("brand").path("name").asText()).isEqualTo("Nike"),
                () -> assertThat(data(detail).has("likeCount")).isFalse()
            );
        }
    }

    @DisplayName("[R-ADMIN-04] 관리자는 상품을 생성·목록 조회·상세 조회·수정·삭제할 수 있다.")
    @Nested
    class AdminProductCrud {

        @DisplayName("[동등 클래스 분할] 브랜드·이름·가격으로 상품을 생성하면 201이고 관리자 상품을 주며 상세 조회로 같은 상품을 확인한다.")
        @Test
        void createsProduct() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");

            // act
            MvcResult created = mockMvc.perform(createProduct(
                    "{\"brandId\":%d,\"name\":\"Air\",\"price\":3000}".formatted(brand.getId())))
                .andExpect(success(HttpStatus.CREATED))
                .andReturn();
            JsonNode product = data(created);

            // assert
            assertAll(
                () -> assertThat(product.path("id").asLong()).isPositive(),
                () -> assertThat(product.path("name").asText()).isEqualTo("Air"),
                () -> assertThat(product.path("price").asLong()).isEqualTo(3_000L),
                () -> assertThat(product.path("brand").path("id").asLong()).isEqualTo(brand.getId()),
                () -> assertThat(product.path("brand").path("name").asText()).isEqualTo("Nike"),
                () -> assertThat(product.path("deleted").asBoolean(true)).isFalse()
            );
            JsonNode found = adminProduct(product.path("id").asLong());
            assertAll(
                () -> assertThat(found.path("name").asText()).isEqualTo("Air"),
                () -> assertThat(found.path("price").asLong()).isEqualTo(3_000L),
                () -> assertThat(found.path("brand").path("id").asLong()).isEqualTo(brand.getId())
            );
        }

        @DisplayName("[오류 추측] 필드가 없거나 타입이 틀리거나 깨진 JSON으로 생성하면 400 INVALID_REQUEST이고 상품이 생기지 않는다.")
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "{\"brandId\":%d,\"name\":\"Air\"}",
            "{\"brandId\":%d,\"price\":3000}",
            "{\"name\":\"Air\",\"price\":3000}",
            "{\"brandId\":%d,\"name\":\"Air\",\"price\":\"abc\"}",
            "{\"brandId\":\"abc\",\"name\":\"Air\",\"price\":3000}",
            "{\"brandId\":%d,\"name\":"
        })
        void rejectsMalformedCreation(String bodyTemplate) throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");

            // act
            mockMvc.perform(createProduct(bodyTemplate.formatted(brand.getId())))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));

            // assert
            mockMvc.perform(get(ADMIN_PRODUCTS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @DisplayName("[동등 클래스 분할] 상품 목록을 조회하면 200이고 등록한 상품을 모두 담는다.")
        @Test
        void listsProducts() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");
            Product air = fixture.product(brand, "Air", 3_000L, 5);
            Product max = fixture.product(brand, "Max", 4_000L, 5);

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_PRODUCTS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(result).path("content")))
                    .containsExactlyInAnyOrder(air.getId(), max.getId()),
                () -> assertThat(data(result).path("totalElements").asLong()).isEqualTo(2L)
            );
        }

        @DisplayName("[동등 클래스 분할] 존재한 적이 없는 상품을 상세 조회하면 404 PRODUCT_NOT_FOUND이다.")
        @Test
        void rejectsMissingProductDetail() throws Exception {
            mockMvc.perform(get(ADMIN_PRODUCT, MISSING_ID).with(admin()))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
        }

        @DisplayName("[상태 전이] 상품 이름과 가격을 수정하면 200이고, 응답과 이후 상세에 새 값이 보이며 재고는 그대로다.")
        @Test
        void updatesProduct() throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act
            mockMvc.perform(updateProduct(product.getId(), "{\"name\":\"Air Max\",\"price\":3500}"))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.name").value("Air Max"))
                .andExpect(jsonPath("$.data.price").value(3_500L))
                .andExpect(jsonPath("$.data.stock").value(5));

            // assert
            JsonNode found = adminProduct(product.getId());
            assertAll(
                () -> assertThat(found.path("name").asText()).isEqualTo("Air Max"),
                () -> assertThat(found.path("price").asLong()).isEqualTo(3_500L),
                () -> assertThat(found.path("stock").asInt()).isEqualTo(5)
            );
        }

        @DisplayName("[오류 추측] 이름이나 가격이 없거나 타입이 틀린 수정 요청은 400 INVALID_REQUEST이고 상품은 그대로다.")
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "{\"price\":3500}",
            "{\"name\":\"Air Max\"}",
            "{\"name\":\"Air Max\",\"price\":\"abc\"}",
            "{\"name\":"
        })
        void rejectsMalformedUpdate(String requestBody) throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act
            mockMvc.perform(updateProduct(product.getId(), requestBody))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));

            // assert
            assertProductUnchanged(product.getId(), "Air", 3_000L, 5);
        }

        @DisplayName("[동등 클래스 분할] 존재하지 않는 상품을 수정하면 404 PRODUCT_NOT_FOUND이다.")
        @Test
        void rejectsMissingProductUpdate() throws Exception {
            mockMvc.perform(updateProduct(MISSING_ID, "{\"name\":\"Air Max\",\"price\":3500}"))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
        }

        @DisplayName("[상태 전이] 상품을 삭제하면 200이고 data가 없으며, 이후 관리자 상세에서 삭제 상태다.")
        @Test
        void deletesProduct() throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act
            MvcResult result = mockMvc.perform(delete(ADMIN_PRODUCT, product.getId()).with(admin()).with(csrf()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertThat(isNullOrAbsent(body(result), "data")).isTrue();
            assertThat(adminProduct(product.getId()).path("deleted").asBoolean(false)).isTrue();
        }

        @DisplayName("[동등 클래스 분할] 존재하지 않는 상품을 삭제하면 404 PRODUCT_NOT_FOUND이다.")
        @Test
        void rejectsMissingProductDeletion() throws Exception {
            mockMvc.perform(delete(ADMIN_PRODUCT, MISSING_ID).with(admin()).with(csrf()))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
        }
    }

    @DisplayName("[R-ADMIN-06] 상품의 이름과 가격은 정해진 유효 범위를 만족해야 한다.")
    @Nested
    class ValidProductName {

        @DisplayName("[동등 클래스 분할] 공백만 있는 이름으로 생성하면 400 INVALID_PRODUCT_NAME이고 상품이 생기지 않는다.")
        @Test
        void rejectsBlankNameOnCreate() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");

            // act
            mockMvc.perform(createProduct(
                    "{\"brandId\":%d,\"name\":\"   \",\"price\":3000}".formatted(brand.getId())))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_NAME"));

            // assert
            mockMvc.perform(get(ADMIN_PRODUCTS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @DisplayName("[동등 클래스 분할] 공백만 있는 이름으로 수정하면 400 INVALID_PRODUCT_NAME이고 이름·가격은 그대로다.")
        @Test
        void rejectsBlankNameOnUpdate() throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act
            mockMvc.perform(updateProduct(product.getId(), "{\"name\":\"   \",\"price\":3500}"))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_NAME"));

            // assert
            assertProductUnchanged(product.getId(), "Air", 3_000L, 5);
        }
    }

    @DisplayName("[P-ADMIN-03] 상품 가격은 1원 이상 1,000,000,000원 이하다.")
    @Nested
    class PriceRange {

        @DisplayName("[경계값 분석] 가격 1과 1,000,000,000으로 생성하면 201이고 그 가격이 저장된다.")
        @ParameterizedTest(name = "가격 {0}")
        @ValueSource(longs = {1L, 1_000_000_000L})
        void acceptsPriceWithinRange(long price) throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");

            // act
            MvcResult created = mockMvc.perform(createProduct(
                    "{\"brandId\":%d,\"name\":\"Air\",\"price\":%d}".formatted(brand.getId(), price)))
                .andExpect(success(HttpStatus.CREATED))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(data(created).path("price").asLong()).isEqualTo(price),
                () -> assertThat(adminProduct(data(created).path("id").asLong()).path("price").asLong())
                    .isEqualTo(price)
            );
        }

        @DisplayName("[경계값 분석] 가격 0과 1,000,000,001로 생성하면 400 INVALID_PRODUCT_PRICE이고 상품이 생기지 않는다.")
        @ParameterizedTest(name = "가격 {0}")
        @ValueSource(longs = {0L, 1_000_000_001L})
        void rejectsPriceOutOfRangeOnCreate(long price) throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");

            // act
            mockMvc.perform(createProduct(
                    "{\"brandId\":%d,\"name\":\"Air\",\"price\":%d}".formatted(brand.getId(), price)))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_PRICE"));

            // assert
            mockMvc.perform(get(ADMIN_PRODUCTS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.totalElements").value(0));
        }

        @DisplayName("[경계값 분석] 가격 0과 1,000,000,001로 수정하면 400 INVALID_PRODUCT_PRICE이고 가격은 그대로다.")
        @ParameterizedTest(name = "가격 {0}")
        @ValueSource(longs = {0L, 1_000_000_001L})
        void rejectsPriceOutOfRangeOnUpdate(long price) throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act
            mockMvc.perform(updateProduct(product.getId(), "{\"name\":\"Air\",\"price\":%d}".formatted(price)))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_PRODUCT_PRICE"));

            // assert
            assertProductUnchanged(product.getId(), "Air", 3_000L, 5);
        }
    }

    @DisplayName("[R-ADMIN-08] 관리자는 상품의 최종 재고 수량을 0 이상으로 설정할 수 있다.")
    @Nested
    class SetFinalStock {

        @DisplayName("[경계값 분석] 재고 5인 상품의 최종 수량을 0, 10으로 바꾸면 200이고 응답과 이후 상세의 stock이 그 값이다.")
        @ParameterizedTest(name = "수량 {0}")
        @ValueSource(ints = {0, 10})
        void setsFinalQuantity(int quantity) throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act
            mockMvc.perform(changeStock(product.getId(), "{\"quantity\":%d}".formatted(quantity)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.id").value(product.getId()))
                .andExpect(jsonPath("$.data.stock").value(quantity));

            // assert
            assertThat(adminProduct(product.getId()).path("stock").asInt(-1)).isEqualTo(quantity);
        }

        @DisplayName("[경계값 분석] 최종 수량 -1이면 400 INVALID_STOCK_QUANTITY이고 재고 5는 그대로다.")
        @Test
        void rejectsNegativeQuantity() throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act
            mockMvc.perform(changeStock(product.getId(), "{\"quantity\":-1}"))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_STOCK_QUANTITY"));

            // assert
            assertProductUnchanged(product.getId(), "Air", 3_000L, 5);
        }

        @DisplayName("[오류 추측] quantity가 없거나 정수가 아니거나 깨진 JSON이면 400 INVALID_REQUEST이고 재고 5는 그대로다.")
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"{}", "{\"quantity\":\"abc\"}", "{\"quantity\":1.5}", "{\"quantity\":"})
        void rejectsMalformedQuantity(String requestBody) throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);

            // act
            mockMvc.perform(changeStock(product.getId(), requestBody))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));

            // assert
            assertProductUnchanged(product.getId(), "Air", 3_000L, 5);
        }

        @DisplayName("[동등 클래스 분할] 존재하지 않는 상품의 재고를 바꾸면 404 PRODUCT_NOT_FOUND이다.")
        @Test
        void rejectsMissingProduct() throws Exception {
            mockMvc.perform(changeStock(MISSING_ID, "{\"quantity\":10}"))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
        }
    }

    @DisplayName("[R-ADMIN-13] 삭제된 브랜드와 상품은 수정하거나 재고를 변경할 수 없다.")
    @Nested
    class NoChangeForDeleted {

        @DisplayName("[상태 전이] 삭제된 상품을 수정하면 404 PRODUCT_NOT_FOUND이고 이름·가격은 그대로다.")
        @Test
        void rejectsUpdatingDeletedProduct() throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);
            fixture.update(Product.class, product.getId(), Product::delete);

            // act
            mockMvc.perform(updateProduct(product.getId(), "{\"name\":\"Air Max\",\"price\":3500}"))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

            // assert
            assertProductUnchanged(product.getId(), "Air", 3_000L, 5);
        }

        @DisplayName("[상태 전이] 삭제된 상품의 재고를 바꾸면 404 PRODUCT_NOT_FOUND이고 재고는 그대로다.")
        @Test
        void rejectsChangingStockOfDeletedProduct() throws Exception {
            // arrange
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);
            fixture.update(Product.class, product.getId(), Product::delete);

            // act
            mockMvc.perform(changeStock(product.getId(), "{\"quantity\":10}"))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

            // assert
            assertProductUnchanged(product.getId(), "Air", 3_000L, 5);
        }

        @DisplayName("[상태 전이] 삭제된 브랜드를 수정하면 404 BRAND_NOT_FOUND이고 이름은 그대로다.")
        @Test
        void rejectsUpdatingDeletedBrand() throws Exception {
            // arrange
            Brand deleted = fixture.deletedBrand("Nike");

            // act
            mockMvc.perform(put(ADMIN_BRAND, deleted.getId())
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Jordan\"}"))
                .andExpect(failure(HttpStatus.NOT_FOUND, "BRAND_NOT_FOUND"));

            // assert
            mockMvc.perform(get(ADMIN_BRAND, deleted.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.name").value("Nike"))
                .andExpect(jsonPath("$.data.deleted").value(true));
        }
    }

    @DisplayName("[P-ADMIN-04] 상품 수정에서 브랜드를 바꾸려 하면 요청 전체를 거절한다.")
    @Nested
    class RejectBrandChange {

        @DisplayName("[의사결정표] brandId를 보내지 않거나 현재 브랜드와 같게 보내면 200이고 이름·가격이 바뀌며 브랜드는 그대로다.")
        @ParameterizedTest(name = "brandId {0}")
        @ValueSource(strings = {"OMITTED", "SAME"})
        void updatesWhenBrandIsKept(String brandField) throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");
            Product product = fixture.product(brand, "Air", 3_000L, 5);
            String requestBody = "OMITTED".equals(brandField)
                ? "{\"name\":\"Air Max\",\"price\":3500}"
                : "{\"name\":\"Air Max\",\"price\":3500,\"brandId\":%d}".formatted(brand.getId());

            // act
            mockMvc.perform(updateProduct(product.getId(), requestBody))
                .andExpect(success(HttpStatus.OK));

            // assert
            JsonNode found = adminProduct(product.getId());
            assertAll(
                () -> assertThat(found.path("name").asText()).isEqualTo("Air Max"),
                () -> assertThat(found.path("price").asLong()).isEqualTo(3_500L),
                () -> assertThat(found.path("brand").path("id").asLong()).isEqualTo(brand.getId())
            );
        }

        @DisplayName("[의사결정표] 다른 브랜드의 brandId를 보내면 409 BRAND_CHANGE_NOT_ALLOWED이고 이름·가격·브랜드가 모두 그대로다.")
        @Test
        void rejectsWholeRequestWhenBrandDiffers() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");
            Brand other = fixture.brand("Puma");
            Product product = fixture.product(brand, "Air", 3_000L, 5);

            // act
            mockMvc.perform(updateProduct(product.getId(),
                    "{\"name\":\"Air Max\",\"price\":3500,\"brandId\":%d}".formatted(other.getId())))
                .andExpect(failure(HttpStatus.CONFLICT, "BRAND_CHANGE_NOT_ALLOWED"));

            // assert
            assertProductUnchanged(product.getId(), "Air", 3_000L, 5);
            assertThat(adminProduct(product.getId()).path("brand").path("id").asLong()).isEqualTo(brand.getId());
        }
    }

    @DisplayName("[P-ADMIN-07] 관리자 상품 목록과 상세에는 삭제된 상품도 삭제 여부와 함께 보여 준다.")
    @Nested
    class ShowDeletedProductsToAdmin {

        @DisplayName("[동등 클래스 분할] 관리자 목록에는 삭제된 상품과 삭제되지 않은 상품이 함께 담기고 deleted가 각각 true, false다.")
        @Test
        void listsDeletedProductWithFlag() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");
            Product active = fixture.product(brand, "Air", 3_000L, 5);
            Product deleted = fixture.deletedProduct(brand, "Old", 1_000L);

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_PRODUCTS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode content = data(result).path("content");
            assertAll(
                () -> assertThat(ids(content)).containsExactlyInAnyOrder(active.getId(), deleted.getId()),
                () -> assertThat(findById(content, active.getId()).path("deleted").asBoolean(true)).isFalse(),
                () -> assertThat(findById(content, deleted.getId()).path("deleted").asBoolean(false)).isTrue()
            );
        }

        @DisplayName("[동등 클래스 분할] 삭제된 상품을 관리자 상세 조회하면 404가 아니라 200이고 deleted가 true다.")
        @Test
        void showsDeletedProductDetail() throws Exception {
            // arrange
            Product deleted = fixture.deletedProduct(fixture.brand("Nike"), "Old", 1_000L);

            // act & assert
            mockMvc.perform(get(ADMIN_PRODUCT, deleted.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.id").value(deleted.getId()))
                .andExpect(jsonPath("$.data.name").value("Old"))
                .andExpect(jsonPath("$.data.deleted").value(true));
        }
    }

    @DisplayName("[P-ADMIN-08] 관리자 상품 목록은 등록 최신순으로 보여 주고 페이지와 브랜드 필터를 제공한다.")
    @Nested
    class LatestPagedFilteredProducts {

        @DisplayName("[동등 클래스 분할] 세 상품을 차례로 등록하면 목록은 나중에 등록한 상품부터 담는다.")
        @Test
        void listsLatestFirst() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");
            Product first = fixture.product(brand, "First", 1_000L, 1);
            Product second = fixture.product(brand, "Second", 1_000L, 1);
            Product third = fixture.product(brand, "Third", 1_000L, 1);

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_PRODUCTS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertThat(ids(data(result).path("content")))
                .containsExactly(third.getId(), second.getId(), first.getId());
        }

        @DisplayName("[동등 클래스 분할] brandId로 거르면 삭제된 상품을 포함해 그 브랜드의 상품만 담는다.")
        @Test
        void filtersByBrand() throws Exception {
            // arrange
            Brand nike = fixture.brand("Nike");
            Brand puma = fixture.brand("Puma");
            Product air = fixture.product(nike, "Air", 1_000L, 1);
            Product old = fixture.deletedProduct(nike, "Old", 1_000L);
            fixture.product(puma, "Speed", 1_000L, 1);

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_PRODUCTS)
                    .param("brandId", String.valueOf(nike.getId()))
                    .with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(result).path("content"))).containsExactlyInAnyOrder(air.getId(), old.getId()),
                () -> assertThat(data(result).path("totalElements").asLong()).isEqualTo(2L)
            );
        }

        @DisplayName("[경계값 분석] 세 상품을 크기 2로 나누면 0페이지에 최신 두 개, 1페이지에 가장 오래된 하나가 있다.")
        @Test
        void splitsIntoPages() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");
            Product first = fixture.product(brand, "First", 1_000L, 1);
            Product second = fixture.product(brand, "Second", 1_000L, 1);
            Product third = fixture.product(brand, "Third", 1_000L, 1);

            // act
            MvcResult page0 = mockMvc.perform(get(ADMIN_PRODUCTS).param("page", "0").param("size", "2").with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult page1 = mockMvc.perform(get(ADMIN_PRODUCTS).param("page", "1").param("size", "2").with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(page0).path("content"))).containsExactly(third.getId(), second.getId()),
                () -> assertThat(data(page0).path("size").asInt()).isEqualTo(2),
                () -> assertThat(data(page0).path("totalElements").asLong()).isEqualTo(3L),
                () -> assertThat(ids(data(page1).path("content"))).containsExactly(first.getId()),
                () -> assertThat(data(page1).path("page").asInt()).isEqualTo(1)
            );
        }

        @DisplayName("[경계값 분석] size 0과 101, page -1은 400 INVALID_REQUEST이다.")
        @ParameterizedTest(name = "page={0}, size={1}")
        @CsvSource({"0, 0", "0, 101", "-1, 20"})
        void rejectsPageOutOfRange(String page, String size) throws Exception {
            // arrange
            fixture.product(fixture.brand("Nike"), "Air", 1_000L, 1);

            // act & assert
            mockMvc.perform(get(ADMIN_PRODUCTS).param("page", page).param("size", size).with(admin()))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }
    }

    @DisplayName("[R-ADMIN-09] 관리자가 변경한 브랜드·상품·재고는 이후 고객 조회에 반영된다.")
    @Nested
    class ReflectAdminChanges {

        @DisplayName("[상태 전이] 관리자가 가격을 3,000에서 3,500으로 바꾸면 고객 상품 상세의 가격이 3,500이다.")
        @Test
        void reflectsPriceChange() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);
            mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(jsonPath("$.data.price").value(3_000L));

            // act
            mockMvc.perform(updateProduct(product.getId(), "{\"name\":\"Air\",\"price\":3500}"))
                .andExpect(success(HttpStatus.OK));

            // assert
            mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.price").value(3_500L));
        }

        @DisplayName("[상태 전이] 관리자가 재고를 0에서 5로 바꾸면 고객 상품의 soldOut이 true에서 false가 된다.")
        @Test
        void reflectsStockChange() throws Exception {
            // arrange
            User customer = fixture.user();
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 0);
            mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(jsonPath("$.data.soldOut").value(true));

            // act
            mockMvc.perform(changeStock(product.getId(), "{\"quantity\":5}"))
                .andExpect(success(HttpStatus.OK));

            // assert
            mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.soldOut").value(false));
        }

        @DisplayName("[상태 전이] 관리자가 브랜드 이름을 바꾸면 고객 브랜드 상세와 고객 상품의 brand.name에 새 이름이 보인다.")
        @Test
        void reflectsBrandNameChange() throws Exception {
            // arrange
            User customer = fixture.user();
            Brand brand = fixture.brand("Nike");
            Product product = fixture.product(brand, "Air", 3_000L, 5);

            // act
            mockMvc.perform(put(ADMIN_BRAND, brand.getId())
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Jordan\"}"))
                .andExpect(success(HttpStatus.OK));

            // assert
            mockMvc.perform(get(CUSTOMER_BRAND, brand.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.name").value("Jordan"));
            mockMvc.perform(get(CUSTOMER_PRODUCT, product.getId()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.brand.name").value("Jordan"));
        }

        @DisplayName("[상태 전이] 관리자가 상품을 삭제하면 고객 상품 상세는 404 PRODUCT_NOT_FOUND이고 고객 목록에서 빠진다.")
        @Test
        void reflectsProductDeletion() throws Exception {
            // arrange
            User customer = fixture.user();
            Brand brand = fixture.brand("Nike");
            Product kept = fixture.product(brand, "Kept", 3_000L, 5);
            Product removed = fixture.product(brand, "Removed", 3_000L, 5);

            // act
            mockMvc.perform(delete(ADMIN_PRODUCT, removed.getId()).with(admin()).with(csrf()))
                .andExpect(success(HttpStatus.OK));

            // assert
            mockMvc.perform(get(CUSTOMER_PRODUCT, removed.getId()).with(customer(customer)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
            MvcResult list = mockMvc.perform(get(CUSTOMER_PRODUCTS).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            assertThat(ids(data(list).path("content"))).containsExactly(kept.getId());
        }
    }

    private MockHttpServletRequestBuilder createProduct(String requestBody) {
        return post(ADMIN_PRODUCTS)
            .with(admin()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody);
    }

    private MockHttpServletRequestBuilder updateProduct(Long productId, String requestBody) {
        return put(ADMIN_PRODUCT, productId)
            .with(admin()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody);
    }

    private MockHttpServletRequestBuilder changeStock(Long productId, String requestBody) {
        return put(ADMIN_STOCK, productId)
            .with(admin()).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody);
    }

    private JsonNode adminProduct(Long productId) throws Exception {
        MvcResult result = mockMvc.perform(get(ADMIN_PRODUCT, productId).with(admin()))
            .andExpect(success(HttpStatus.OK))
            .andReturn();
        return data(result);
    }

    private void assertProductUnchanged(Long productId, String name, long price, int stock) throws Exception {
        JsonNode found = adminProduct(productId);
        assertAll(
            () -> assertThat(found.path("name").asText()).isEqualTo(name),
            () -> assertThat(found.path("price").asLong()).isEqualTo(price),
            () -> assertThat(found.path("stock").asInt(-1)).isEqualTo(stock)
        );
    }
}
