package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.brand.domain.Brand;
import com.loopers.order.domain.Order;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
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

import java.util.Arrays;
import java.util.stream.Stream;

import static com.loopers.support.http.ApiHttp.admin;
import static com.loopers.support.http.ApiHttp.body;
import static com.loopers.support.http.ApiHttp.content;
import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.data;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.findById;
import static com.loopers.support.http.ApiHttp.nonAdmin;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class RequesterAccessHttpTest {

    private static final String UNKNOWN_USER_ID = "999999";
    private static final String NON_NUMERIC_USER_ID = "abc";

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

    @DisplayName("[R-ACCESS-01] 고객은 브랜드와 상품을 조회하고 좋아요·포인트·주문 기능을 이용할 수 있다.")
    @Nested
    class CustomerFeatures {

        @DisplayName("[동등 클래스 분할] 식별된 고객은 브랜드 상세, 상품 목록, 상품 상세를 조회할 수 있다.")
        @Test
        void readsBrandsAndProducts() throws Exception {
            // arrange
            Scene scene = scene();

            // act & assert
            mockMvc.perform(get("/api/v1/brands/{brandId}", scene.brand().getId()).with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(get("/api/v1/products").with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(get("/api/v1/products/{productId}", scene.product().getId())
                    .with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK));
        }

        @DisplayName("[동등 클래스 분할] 식별된 고객은 좋아요를 등록하고 내 좋아요 목록을 조회하고 취소할 수 있다.")
        @Test
        void usesLikes() throws Exception {
            // arrange
            Scene scene = scene();

            // act & assert
            mockMvc.perform(post("/api/v1/products/{productId}/likes", scene.product().getId())
                    .with(customer(scene.customer())).with(csrf()))
                .andExpect(success(HttpStatus.CREATED));
            mockMvc.perform(get("/api/v1/users/{userId}/likes", scene.customer().getId())
                    .with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(delete("/api/v1/products/{productId}/likes", scene.product().getId())
                    .with(customer(scene.customer())).with(csrf()))
                .andExpect(success(HttpStatus.OK));
        }

        @DisplayName("[동등 클래스 분할] 식별된 고객은 포인트를 충전하고 잔액을 조회할 수 있다.")
        @Test
        void usesPoints() throws Exception {
            // arrange
            Scene scene = scene();

            // act & assert
            mockMvc.perform(post("/api/v1/points/charge")
                    .with(customer(scene.customer())).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":1000}"))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(get("/api/v1/points").with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK));
        }

        @DisplayName("[동등 클래스 분할] 식별된 고객은 주문을 생성·확정하고 내 주문 목록과 상세를 조회할 수 있다.")
        @Test
        void usesOrders() throws Exception {
            // arrange
            Scene scene = scene();

            // act
            MvcResult created = mockMvc.perform(post("/api/v1/orders")
                    .with(customer(scene.customer())).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(orderBody(scene.product(), 1)))
                .andExpect(success(HttpStatus.CREATED))
                .andReturn();
            long orderId = data(created).path("id").asLong();

            // assert
            mockMvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId)
                    .with(customer(scene.customer())).with(csrf()))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(get("/api/v1/orders").with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(get("/api/v1/orders/{orderId}", orderId).with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK));
        }
    }

    @DisplayName("[R-ACCESS-02] 관리자는 브랜드·상품·재고를 관리하고 구매자들의 주문을 조회할 수 있다.")
    @Nested
    class AdminFeatures {

        @DisplayName("[동등 클래스 분할] ADMIN 역할은 브랜드를 생성·목록 조회·상세 조회·수정·삭제할 수 있다.")
        @Test
        void managesBrands() throws Exception {
            // act
            MvcResult created = mockMvc.perform(post("/api-admin/v1/brands")
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Nike\"}"))
                .andExpect(success(HttpStatus.CREATED))
                .andReturn();
            long brandId = data(created).path("id").asLong();

            // assert
            mockMvc.perform(get("/api-admin/v1/brands").with(admin()))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(get("/api-admin/v1/brands/{brandId}", brandId).with(admin()))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(put("/api-admin/v1/brands/{brandId}", brandId)
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Jordan\"}"))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(delete("/api-admin/v1/brands/{brandId}", brandId).with(admin()).with(csrf()))
                .andExpect(success(HttpStatus.OK));
        }

        @DisplayName("[동등 클래스 분할] ADMIN 역할은 상품을 생성·목록 조회·상세 조회·수정하고 재고를 바꾸고 삭제할 수 있다.")
        @Test
        void managesProductsAndStock() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");

            // act
            MvcResult created = mockMvc.perform(post("/api-admin/v1/products")
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brandId\":%d,\"name\":\"Air\",\"price\":1000}".formatted(brand.getId())))
                .andExpect(success(HttpStatus.CREATED))
                .andReturn();
            long productId = data(created).path("id").asLong();

            // assert
            mockMvc.perform(get("/api-admin/v1/products").with(admin()))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(get("/api-admin/v1/products/{productId}", productId).with(admin()))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(put("/api-admin/v1/products/{productId}", productId)
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Air Max\",\"price\":2000}"))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(put("/api-admin/v1/products/{productId}/stock", productId)
                    .with(admin()).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quantity\":5}"))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(delete("/api-admin/v1/products/{productId}", productId).with(admin()).with(csrf()))
                .andExpect(success(HttpStatus.OK));
        }

        @DisplayName("[동등 클래스 분할] ADMIN 역할은 여러 구매자의 주문 목록과 상세를 조회할 수 있다.")
        @Test
        void readsBuyersOrders() throws Exception {
            // arrange
            Brand brand = fixture.brand("Nike");
            Product product = fixture.product(brand, "Air", 1_000L, 10);
            Order first = fixture.draftOrder(fixture.user(), fixture.item(product, 1));
            Order second = fixture.draftOrder(fixture.user(), fixture.item(product, 2));

            // act & assert
            mockMvc.perform(get("/api-admin/v1/orders").with(admin()))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(get("/api-admin/v1/orders/{orderId}", first.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK));
            mockMvc.perform(get("/api-admin/v1/orders/{orderId}", second.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK));
        }
    }

    @DisplayName("[R-ACCESS-04] 관리자 기능은 관리자만 사용할 수 있고 일반 사용자와 식별되지 않은 사용자는 사용할 수 없다.")
    @Nested
    class AdminOnly {

        @DisplayName("[의사결정표] 브랜드 목록 요청은 ADMIN 역할이면 200, USER 역할이나 식별되지 않은 요청이면 403이다.")
        @ParameterizedTest(name = "요청자 {0} → {1}")
        @CsvSource({"ADMIN, 200", "USER, 403", "NONE, 403"})
        void allowsOnlyAdminRole(String requester, int expectedStatus) throws Exception {
            // arrange
            MockHttpServletRequestBuilder request = get("/api-admin/v1/brands");
            if ("ADMIN".equals(requester)) {
                request.with(admin());
            } else if ("USER".equals(requester)) {
                request.with(nonAdmin());
            }

            // act & assert
            mockMvc.perform(request).andExpect(status().is(expectedStatus));
        }

        @DisplayName("[동등 클래스 분할] USER 역할과 식별되지 않은 요청은 브랜드·상품·주문의 관리자 조회에서 403이다.")
        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("com.loopers.interfaces.api.RequesterAccessHttpTest#nonAdminReads")
        void rejectsNonAdminReads(String requester, AdminRead read) throws Exception {
            // arrange
            AdminScene scene = adminScene();
            MockHttpServletRequestBuilder request = read.request(scene);
            if ("USER".equals(requester)) {
                request.with(nonAdmin());
            }

            // act
            MvcResult result = mockMvc.perform(request).andReturn();

            // assert
            assertAll(
                () -> assertThat(result.getResponse().getStatus()).isEqualTo(403),
                () -> assertThat(content(result)).doesNotContain("errorCode")
            );
        }

        @DisplayName("[의사결정표] USER 역할의 관리자 변경 요청은 유효한 CSRF가 있어도 403이고, 브랜드와 상품은 그대로다.")
        @ParameterizedTest(name = "{0}")
        @EnumSource(AdminWrite.class)
        void rejectsUserRoleWritesAndKeepsState(AdminWrite write) throws Exception {
            // arrange
            AdminScene scene = adminScene();

            // act
            MvcResult result = mockMvc.perform(write.request(scene).with(nonAdmin()).with(csrf())).andReturn();

            // assert
            assertAll(
                () -> assertThat(result.getResponse().getStatus()).isEqualTo(403),
                () -> assertThat(content(result)).doesNotContain("errorCode")
            );
            assertAdminSceneUnchanged(scene);
        }

        @DisplayName("[의사결정표] 식별되지 않은 관리자 변경 요청은 유효한 CSRF가 있어도 403이고, 브랜드와 상품은 그대로다.")
        @ParameterizedTest(name = "{0}")
        @EnumSource(AdminWrite.class)
        void rejectsAnonymousWritesAndKeepsState(AdminWrite write) throws Exception {
            // arrange
            AdminScene scene = adminScene();

            // act
            MvcResult result = mockMvc.perform(write.request(scene).with(csrf())).andReturn();

            // assert
            assertAll(
                () -> assertThat(result.getResponse().getStatus()).isEqualTo(403),
                () -> assertThat(content(result)).doesNotContain("errorCode")
            );
            assertAdminSceneUnchanged(scene);
        }

        private void assertAdminSceneUnchanged(AdminScene scene) throws Exception {
            MvcResult brands = mockMvc.perform(get("/api-admin/v1/brands").with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult product = mockMvc.perform(get("/api-admin/v1/products/{productId}", scene.product().getId())
                    .with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            JsonNode brandContent = data(brands).path("content");
            JsonNode nike = findById(brandContent, scene.brand().getId());
            JsonNode puma = findById(brandContent, scene.emptyBrand().getId());
            JsonNode productData = data(product);
            assertAll(
                () -> assertThat(data(brands).path("totalElements").asLong()).isEqualTo(2L),
                () -> assertThat(nike.path("name").asText()).isEqualTo("Nike"),
                () -> assertThat(nike.path("deleted").asBoolean(true)).isFalse(),
                () -> assertThat(puma.path("name").asText()).isEqualTo("Puma"),
                () -> assertThat(puma.path("deleted").asBoolean(true)).isFalse(),
                () -> assertThat(productData.path("name").asText()).isEqualTo("Air"),
                () -> assertThat(productData.path("price").asLong()).isEqualTo(1_000L),
                () -> assertThat(productData.path("stock").asInt()).isEqualTo(5),
                () -> assertThat(productData.path("deleted").asBoolean(true)).isFalse()
            );
            mockMvc.perform(get("/api-admin/v1/products").with(admin()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
        }
    }

    @DisplayName("[R-ACCESS-05] 식별 정보가 없거나 존재하지 않는 사용자의 고객 요청은 거절한다.")
    @Nested
    class RejectUnidentifiedCustomer {

        @DisplayName("[동등 클래스 분할] X-USER-ID가 없거나 숫자가 아니거나 없는 사용자이면 모든 고객 API가 401 USER_NOT_IDENTIFIED이다.")
        @ParameterizedTest(name = "{0} / X-USER-ID={1}")
        @MethodSource("com.loopers.interfaces.api.RequesterAccessHttpTest#unidentifiedCustomerRequests")
        void rejectsEveryCustomerApi(CustomerApi api, String userIdHeader) throws Exception {
            // arrange
            Scene scene = scene();

            // act & assert
            mockMvc.perform(api.request(scene).with(customer(userIdHeader)).with(csrf()))
                .andExpect(failure(HttpStatus.UNAUTHORIZED, "USER_NOT_IDENTIFIED"));
        }

        @DisplayName("[오류 추측] 식별되지 않은 충전·좋아요 등록·주문 생성·주문 확정은 거절되고, 잔액·좋아요 수·주문·재고는 그대로다.")
        @ParameterizedTest(name = "X-USER-ID={0}")
        @NullSource
        @ValueSource(strings = {NON_NUMERIC_USER_ID, UNKNOWN_USER_ID})
        void keepsStateWhenCustomerIsUnidentified(String userIdHeader) throws Exception {
            // arrange
            Scene scene = scene();

            // act
            for (CustomerApi api : new CustomerApi[] {
                CustomerApi.POINT_CHARGE, CustomerApi.LIKE_REGISTER, CustomerApi.ORDER_CREATE, CustomerApi.ORDER_CONFIRM
            }) {
                mockMvc.perform(api.request(scene).with(customer(userIdHeader)).with(csrf()))
                    .andExpect(failure(HttpStatus.UNAUTHORIZED, "USER_NOT_IDENTIFIED"));
            }

            // assert
            mockMvc.perform(get("/api/v1/points").with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.balance").value(10_000L));
            mockMvc.perform(get("/api/v1/products/{productId}", scene.product().getId())
                    .with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.likeCount").value(0));
            mockMvc.perform(get("/api/v1/orders/{orderId}", scene.order().getId()).with(customer(scene.customer())))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
            mockMvc.perform(get("/api-admin/v1/orders").with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.totalElements").value(1));
            mockMvc.perform(get("/api-admin/v1/products/{productId}", scene.product().getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.stock").value(10));
        }
    }

    @DisplayName("[P-ACCESS-01] 식별 정보가 없는 요청과 존재하지 않는 사용자의 요청을 같은 결과로 거절한다.")
    @Nested
    class SameRejectionForUnidentified {

        @DisplayName("[동등 클래스 분할] X-USER-ID가 없는 요청과 없는 사용자의 요청은 상태 코드와 응답 본문이 모두 같다.")
        @ParameterizedTest(name = "{0}")
        @EnumSource(CustomerApi.class)
        void returnsSameResponse(CustomerApi api) throws Exception {
            // arrange
            Scene scene = scene();

            // act
            MvcResult missing = mockMvc.perform(api.request(scene).with(csrf())).andReturn();
            MvcResult unknown = mockMvc.perform(api.request(scene).with(customer(UNKNOWN_USER_ID)).with(csrf()))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(missing.getResponse().getStatus()).isEqualTo(401),
                () -> assertThat(body(missing).path("meta").path("errorCode").asText())
                    .isEqualTo("USER_NOT_IDENTIFIED"),
                () -> assertThat(unknown.getResponse().getStatus()).isEqualTo(missing.getResponse().getStatus()),
                () -> assertThat(content(unknown)).isEqualTo(content(missing))
            );
        }
    }

    static Stream<Arguments> unidentifiedCustomerRequests() {
        return Arrays.stream(CustomerApi.values())
            .flatMap(api -> Stream.of(
                Arguments.of(api, null),
                Arguments.of(api, NON_NUMERIC_USER_ID),
                Arguments.of(api, UNKNOWN_USER_ID)
            ));
    }

    static Stream<Arguments> nonAdminReads() {
        return Arrays.stream(AdminRead.values())
            .flatMap(read -> Stream.of(Arguments.of("USER", read), Arguments.of("NONE", read)));
    }

    private Scene scene() {
        User customer = fixture.userWithPoint(10_000L);
        Brand brand = fixture.brand("Nike");
        Product product = fixture.product(brand, "Air", 1_000L, 10);
        Order order = fixture.draftOrder(customer, fixture.item(product, 1));
        return new Scene(customer, brand, product, order);
    }

    private AdminScene adminScene() {
        Brand brand = fixture.brand("Nike");
        Brand emptyBrand = fixture.brand("Puma");
        Product product = fixture.product(brand, "Air", 1_000L, 5);
        Order order = fixture.draftOrder(fixture.user(), fixture.item(product, 1));
        return new AdminScene(brand, emptyBrand, product, order);
    }

    private static String orderBody(Product product, int quantity) {
        return "{\"items\":[{\"productId\":%d,\"quantity\":%d}]}".formatted(product.getId(), quantity);
    }

    record Scene(User customer, Brand brand, Product product, Order order) {
    }

    record AdminScene(Brand brand, Brand emptyBrand, Product product, Order order) {
    }

    enum CustomerApi {
        BRAND_DETAIL {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return get("/api/v1/brands/{brandId}", scene.brand().getId());
            }
        },
        PRODUCT_LIST {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return get("/api/v1/products");
            }
        },
        PRODUCT_DETAIL {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return get("/api/v1/products/{productId}", scene.product().getId());
            }
        },
        LIKE_REGISTER {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return post("/api/v1/products/{productId}/likes", scene.product().getId());
            }
        },
        LIKE_CANCEL {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return delete("/api/v1/products/{productId}/likes", scene.product().getId());
            }
        },
        MY_LIKES {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return get("/api/v1/users/{userId}/likes", scene.customer().getId());
            }
        },
        POINT_CHARGE {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return post("/api/v1/points/charge")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":1000}");
            }
        },
        POINT_BALANCE {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return get("/api/v1/points");
            }
        },
        ORDER_CREATE {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return post("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(orderBody(scene.product(), 1));
            }
        },
        ORDER_CONFIRM {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return post("/api/v1/orders/{orderId}/confirm", scene.order().getId());
            }
        },
        MY_ORDERS {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return get("/api/v1/orders");
            }
        },
        MY_ORDER_DETAIL {
            @Override
            MockHttpServletRequestBuilder request(Scene scene) {
                return get("/api/v1/orders/{orderId}", scene.order().getId());
            }
        };

        abstract MockHttpServletRequestBuilder request(Scene scene);
    }

    enum AdminRead {
        BRAND_LIST {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return get("/api-admin/v1/brands");
            }
        },
        BRAND_DETAIL {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return get("/api-admin/v1/brands/{brandId}", scene.brand().getId());
            }
        },
        PRODUCT_LIST {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return get("/api-admin/v1/products");
            }
        },
        PRODUCT_DETAIL {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return get("/api-admin/v1/products/{productId}", scene.product().getId());
            }
        },
        ORDER_LIST {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return get("/api-admin/v1/orders");
            }
        },
        ORDER_DETAIL {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return get("/api-admin/v1/orders/{orderId}", scene.order().getId());
            }
        };

        abstract MockHttpServletRequestBuilder request(AdminScene scene);
    }

    enum AdminWrite {
        CREATE_BRAND {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return post("/api-admin/v1/brands")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Adidas\"}");
            }
        },
        UPDATE_BRAND {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return put("/api-admin/v1/brands/{brandId}", scene.brand().getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Jordan\"}");
            }
        },
        DELETE_BRAND {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return delete("/api-admin/v1/brands/{brandId}", scene.emptyBrand().getId());
            }
        },
        CREATE_PRODUCT {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return post("/api-admin/v1/products")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"brandId\":%d,\"name\":\"Air Max\",\"price\":2000}"
                        .formatted(scene.brand().getId()));
            }
        },
        UPDATE_PRODUCT {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return put("/api-admin/v1/products/{productId}", scene.product().getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Air Max\",\"price\":2000}");
            }
        },
        CHANGE_STOCK {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return put("/api-admin/v1/products/{productId}/stock", scene.product().getId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quantity\":10}");
            }
        },
        DELETE_PRODUCT {
            @Override
            MockHttpServletRequestBuilder request(AdminScene scene) {
                return delete("/api-admin/v1/products/{productId}", scene.product().getId());
            }
        };

        abstract MockHttpServletRequestBuilder request(AdminScene scene);
    }
}
