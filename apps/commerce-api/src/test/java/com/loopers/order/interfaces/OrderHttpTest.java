package com.loopers.order.interfaces;

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

import static com.loopers.support.http.ApiHttp.admin;
import static com.loopers.support.http.ApiHttp.content;
import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.data;
import static com.loopers.support.http.ApiHttp.failure;
import static com.loopers.support.http.ApiHttp.findById;
import static com.loopers.support.http.ApiHttp.findByProductId;
import static com.loopers.support.http.ApiHttp.ids;
import static com.loopers.support.http.ApiHttp.isNullOrAbsent;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class OrderHttpTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final String ORDER = "/api/v1/orders/{orderId}";
    private static final String CONFIRM = "/api/v1/orders/{orderId}/confirm";
    private static final String POINTS = "/api/v1/points";
    private static final String ADMIN_ORDERS = "/api-admin/v1/orders";
    private static final String ADMIN_ORDER = "/api-admin/v1/orders/{orderId}";
    private static final String ADMIN_PRODUCT = "/api-admin/v1/products/{productId}";
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

    @DisplayName("[R-ORDER-01] 고객은 여러 상품과 각 상품의 수량으로 주문을 생성할 수 있다.")
    @Nested
    class CreateWithItems {

        @DisplayName("[동등 클래스 분할] 상품 A 2개와 B 1개로 주문하면 201이고, 두 품목과 수량을 담은 DRAFT 주문 상세를 주며 상세 조회로 확인한다.")
        @Test
        void createsOrder() throws Exception {
            // arrange
            User customer = fixture.user();
            Catalog catalog = catalog();

            // act
            MvcResult created = mockMvc.perform(createOrder(customer, itemsBody(
                    catalog.a().getId(), 2, catalog.b().getId(), 1)))
                .andExpect(success(HttpStatus.CREATED))
                .andReturn();

            // assert
            JsonNode order = data(created);
            assertAll(
                () -> assertThat(order.path("id").asLong()).isPositive(),
                () -> assertThat(order.path("status").asText()).isEqualTo("DRAFT"),
                () -> assertThat(isNullOrAbsent(order, "payment")).isTrue(),
                () -> assertThat(order.path("items").size()).isEqualTo(2),
                () -> assertThat(findByProductId(order.path("items"), catalog.a().getId()).path("quantity").asInt())
                    .isEqualTo(2),
                () -> assertThat(findByProductId(order.path("items"), catalog.b().getId()).path("quantity").asInt())
                    .isEqualTo(1)
            );
            mockMvc.perform(get(ORDER, order.path("id").asLong()).with(customer(customer)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items.length()").value(2));
        }

        @DisplayName("[오류 추측] items나 quantity가 없거나 quantity가 정수가 아니거나 깨진 JSON이면 400 INVALID_REQUEST이고 주문이 생기지 않는다.")
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "{}",
            "{\"items\":[{\"productId\":%d}]}",
            "{\"items\":[{\"productId\":%d,\"quantity\":\"abc\"}]}",
            "{\"items\":[{\"productId\":%d,\"quantity\":1.5}]}",
            "{\"items\":[{\"productId\":%d,\"quantity\":"
        })
        void rejectsMalformedRequest(String bodyTemplate) throws Exception {
            // arrange
            User customer = fixture.user();
            Catalog catalog = catalog();

            // act
            mockMvc.perform(createOrder(customer, bodyTemplate.formatted(catalog.a().getId())))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));

            // assert
            assertNoOrders(customer);
        }
    }

    @DisplayName("[R-ORDER-05] 존재하지 않거나 삭제된 상품은 주문에 사용할 수 없다.")
    @Nested
    class UnavailableProduct {

        @DisplayName("[동등 클래스 분할] 존재하지 않는 상품으로 주문하면 404 PRODUCT_NOT_FOUND이고 주문이 생기지 않는다.")
        @Test
        void rejectsMissingProduct() throws Exception {
            // arrange
            User customer = fixture.user();

            // act
            mockMvc.perform(createOrder(customer, itemsBody(MISSING_ID, 1)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

            // assert
            assertNoOrders(customer);
        }

        @DisplayName("[동등 클래스 분할] 삭제된 상품으로 주문하면 404 PRODUCT_NOT_FOUND이고 주문이 생기지 않는다.")
        @Test
        void rejectsDeletedProduct() throws Exception {
            // arrange
            User customer = fixture.user();
            Product deleted = fixture.deletedProduct(fixture.brand("Nike"), "Old", 1_000L);

            // act
            mockMvc.perform(createOrder(customer, itemsBody(deleted.getId(), 1)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

            // assert
            assertNoOrders(customer);
        }

        @DisplayName("[오류 추측] 판매 중인 상품과 삭제된 상품을 함께 주문해도 404 PRODUCT_NOT_FOUND이고 주문이 생기지 않는다.")
        @Test
        void rejectsWhenAnyProductIsDeleted() throws Exception {
            // arrange
            User customer = fixture.user();
            Catalog catalog = catalog();
            Product deleted = fixture.deletedProduct(fixture.brand("Puma"), "Old", 1_000L);

            // act
            mockMvc.perform(createOrder(customer, itemsBody(catalog.a().getId(), 1, deleted.getId(), 1)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

            // assert
            assertNoOrders(customer);
        }
    }

    @DisplayName("[R-ORDER-06] 주문할 상품의 수량은 양수여야 한다.")
    @Nested
    class PositiveQuantity {

        @DisplayName("[경계값 분석] 수량 0과 -1이면 400 INVALID_ORDER_QUANTITY이고 주문이 생기지 않는다.")
        @ParameterizedTest(name = "수량 {0}")
        @ValueSource(ints = {0, -1})
        void rejectsNonPositiveQuantity(int quantity) throws Exception {
            // arrange
            User customer = fixture.user();
            Catalog catalog = catalog();

            // act
            mockMvc.perform(createOrder(customer, itemsBody(catalog.a().getId(), quantity)))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_ORDER_QUANTITY"));

            // assert
            assertNoOrders(customer);
        }

        @DisplayName("[경계값 분석] 수량 1이면 201이고 품목 수량이 1이다.")
        @Test
        void acceptsOne() throws Exception {
            // arrange
            User customer = fixture.user();
            Catalog catalog = catalog();

            // act & assert
            mockMvc.perform(createOrder(customer, itemsBody(catalog.a().getId(), 1)))
                .andExpect(success(HttpStatus.CREATED))
                .andExpect(jsonPath("$.data.items[0].productId").value(catalog.a().getId()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(1));
        }
    }

    @DisplayName("[P-ORDER-01] 품목이 하나도 없는 주문은 거절한다.")
    @Nested
    class NonEmptyItems {

        @DisplayName("[경계값 분석] items가 빈 배열이면 400 EMPTY_ORDER_ITEMS이고 주문이 생기지 않는다.")
        @Test
        void rejectsEmptyItems() throws Exception {
            // arrange
            User customer = fixture.user();

            // act
            mockMvc.perform(createOrder(customer, "{\"items\":[]}"))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "EMPTY_ORDER_ITEMS"));

            // assert
            assertNoOrders(customer);
        }
    }

    @DisplayName("[R-ORDER-13] 고객은 자신의 주문 목록과 상세를 조회할 수 있다.")
    @Nested
    class FindMine {

        @DisplayName("[동등 클래스 분할] 고객 A의 주문 목록에는 A의 주문 2개만 담기고 totalElements는 2다.")
        @Test
        void listsOwnOrders() throws Exception {
            // arrange
            Catalog catalog = catalog();
            User me = fixture.user();
            User other = fixture.user();
            Order first = fixture.draftOrder(me, fixture.item(catalog.a(), 1));
            fixture.draftOrder(other, fixture.item(catalog.a(), 1));
            Order second = fixture.draftOrder(me, fixture.item(catalog.b(), 1));

            // act
            MvcResult result = mockMvc.perform(get(ORDERS).with(customer(me)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(result).path("content")))
                    .containsExactlyInAnyOrder(first.getId(), second.getId()),
                () -> assertThat(data(result).path("totalElements").asLong()).isEqualTo(2L)
            );
        }

        @DisplayName("[동등 클래스 분할] 고객은 자신의 주문 상세를 200으로 조회한다.")
        @Test
        void readsOwnOrder() throws Exception {
            // arrange
            User me = fixture.user();
            Order order = fixture.draftOrder(me, fixture.item(catalog().a(), 1));

            // act & assert
            mockMvc.perform(get(ORDER, order.getId()).with(customer(me)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.id").value(order.getId()));
        }
    }

    @DisplayName("[R-ORDER-14] 고객은 주문에서 품목·수량·금액·상태·결제액을 확인할 수 있다.")
    @Nested
    class ItemsAmountsStatusPayment {

        @DisplayName("[상태 전이] 확정된 주문 상세에는 품목별 상품·이름·수량·단가·금액, 합계 7,000, CONFIRMED, 결제액 7,000과 결제 시점이 있다.")
        @Test
        void showsConfirmedOrder() throws Exception {
            // arrange
            User me = fixture.user();
            Catalog catalog = catalog();
            Order order = fixture.confirmedOrder(me, fixture.item(catalog.a(), 2), fixture.item(catalog.b(), 1));

            // act
            MvcResult result = mockMvc.perform(get(ORDER, order.getId()).with(customer(me)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertConfirmedDetail(data(result), catalog);
        }

        @DisplayName("[상태 전이] 확정 전 주문 상세는 DRAFT이고 합계 4,000이며 payment가 null이다.")
        @Test
        void showsDraftOrder() throws Exception {
            // arrange
            User me = fixture.user();
            Catalog catalog = catalog();
            Order order = fixture.draftOrder(me, fixture.item(catalog.a(), 2));

            // act
            MvcResult result = mockMvc.perform(get(ORDER, order.getId()).with(customer(me)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode detail = data(result);
            assertAll(
                () -> assertThat(detail.path("status").asText()).isEqualTo("DRAFT"),
                () -> assertThat(detail.path("totalAmount").asLong()).isEqualTo(4_000L),
                () -> assertThat(isNullOrAbsent(detail, "payment")).isTrue()
            );
        }
    }

    @DisplayName("[P-ACCESS-02] 고객이 다른 고객의 주문을 요청하면 없는 대상으로 알린다.")
    @Nested
    class OthersOrderAsNotFound {

        @DisplayName("[동등 클래스 분할] 다른 고객의 주문 상세는 404 ORDER_NOT_FOUND이고, 존재하지 않는 주문을 요청한 응답과 같다.")
        @Test
        void hidesOthersOrderDetail() throws Exception {
            // arrange
            User me = fixture.user();
            User owner = fixture.user();
            Order order = fixture.draftOrder(owner, fixture.item(catalog().a(), 1));

            // act
            MvcResult others = mockMvc.perform(get(ORDER, order.getId()).with(customer(me)))
                .andExpect(failure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"))
                .andReturn();
            MvcResult missing = mockMvc.perform(get(ORDER, MISSING_ID).with(customer(me))).andReturn();

            // assert
            assertAll(
                () -> assertThat(missing.getResponse().getStatus()).isEqualTo(others.getResponse().getStatus()),
                () -> assertThat(content(missing)).isEqualTo(content(others))
            );
        }

        @DisplayName("[의사결정표] 다른 고객의 DRAFT 주문을 확정하면 404 ORDER_NOT_FOUND이고 주문 상태·재고·두 고객의 잔액이 그대로다.")
        @Test
        void rejectsConfirmingOthersDraft() throws Exception {
            // arrange
            User me = fixture.userWithPoint(10_000L);
            User owner = fixture.userWithPoint(10_000L);
            Catalog catalog = catalog();
            Order order = fixture.draftOrder(owner, fixture.item(catalog.a(), 2));

            // act
            mockMvc.perform(confirm(me, order.getId()))
                .andExpect(failure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

            // assert
            mockMvc.perform(get(ORDER, order.getId()).with(customer(owner)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
            assertAll(
                () -> assertThat(stockOf(catalog.a())).isEqualTo(5),
                () -> assertThat(balanceOf(me)).isEqualTo(10_000L),
                () -> assertThat(balanceOf(owner)).isEqualTo(10_000L)
            );
        }

        @DisplayName("[의사결정표] 다른 고객의 확정된 주문을 확정하면 409가 아니라 404 ORDER_NOT_FOUND이다.")
        @Test
        void hidesOthersConfirmedOrder() throws Exception {
            // arrange
            User me = fixture.userWithPoint(10_000L);
            User owner = fixture.user();
            Order order = fixture.confirmedOrder(owner, fixture.item(catalog().a(), 1));

            // act & assert
            mockMvc.perform(confirm(me, order.getId()))
                .andExpect(failure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
        }
    }

    @DisplayName("[P-ORDER-04] 이미 확정된 주문을 다시 확정하면 거절한다.")
    @Nested
    class RejectReconfirmation {

        @DisplayName("[상태 전이] 확정된 주문을 다시 확정하면 409 ORDER_ALREADY_CONFIRMED이고 결제액·잔액·재고가 그대로다.")
        @Test
        void rejectsAndKeepsPayment() throws Exception {
            // arrange
            User me = fixture.userWithPoint(10_000L);
            Catalog catalog = catalog();
            Order order = fixture.confirmedOrder(me, fixture.item(catalog.a(), 2));

            // act
            mockMvc.perform(confirm(me, order.getId()))
                .andExpect(failure(HttpStatus.CONFLICT, "ORDER_ALREADY_CONFIRMED"));

            // assert
            mockMvc.perform(get(ORDER, order.getId()).with(customer(me)))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.payment.amount").value(4_000L));
            assertAll(
                () -> assertThat(balanceOf(me)).isEqualTo(10_000L),
                () -> assertThat(stockOf(catalog.a())).isEqualTo(5)
            );
        }
    }

    @DisplayName("[P-ORDER-07] 주문 품목의 상품 정보는 주문 당시의 값으로 보여 주고, 상품이 삭제되어도 저장된 값으로 보여 준다.")
    @Nested
    class SnapshotItemValues {

        @DisplayName("[상태 전이] 주문 뒤 상품 이름과 가격이 Air·3,000에서 Air Max·3,500으로 바뀌어도 고객·관리자 주문 상세의 품목은 Air·3,000이다.")
        @Test
        void keepsValuesAfterProductUpdate() throws Exception {
            // arrange
            User me = fixture.user();
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);
            Order order = fixture.draftOrder(me, fixture.item(product, 2));

            // act
            fixture.update(Product.class, product.getId(),
                found -> found.update("Air Max", 3_500L, found.getBrandId()));

            // assert
            assertSnapshotItem(me, order, product);
        }

        @DisplayName("[상태 전이] 주문 뒤 상품이 삭제되어도 고객·관리자 주문 상세에 품목이 Air·3,000으로 남는다.")
        @Test
        void keepsValuesAfterProductDeletion() throws Exception {
            // arrange
            User me = fixture.user();
            Product product = fixture.product(fixture.brand("Nike"), "Air", 3_000L, 5);
            Order order = fixture.draftOrder(me, fixture.item(product, 2));

            // act
            fixture.update(Product.class, product.getId(), Product::delete);

            // assert
            assertSnapshotItem(me, order, product);
        }

        private void assertSnapshotItem(User me, Order order, Product product) throws Exception {
            MvcResult customerDetail = mockMvc.perform(get(ORDER, order.getId()).with(customer(me)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult adminDetail = mockMvc.perform(get(ADMIN_ORDER, order.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            JsonNode customerItem = findByProductId(data(customerDetail).path("items"), product.getId());
            JsonNode adminItem = findByProductId(data(adminDetail).path("items"), product.getId());
            assertAll(
                () -> assertThat(customerItem.path("productName").asText()).isEqualTo("Air"),
                () -> assertThat(customerItem.path("unitPrice").asLong()).isEqualTo(3_000L),
                () -> assertThat(customerItem.path("amount").asLong()).isEqualTo(6_000L),
                () -> assertThat(adminItem.path("productName").asText()).isEqualTo("Air"),
                () -> assertThat(adminItem.path("unitPrice").asLong()).isEqualTo(3_000L),
                () -> assertThat(adminItem.path("amount").asLong()).isEqualTo(6_000L)
            );
        }
    }

    @DisplayName("[P-ORDER-08] 주문 목록은 품목 수와 합계만 보여 주고, 품목 전체는 상세에서 보여 준다.")
    @Nested
    class SummaryInList {

        @DisplayName("[동등 클래스 분할] 고객 주문 목록 항목은 품목 수 2와 합계 7,000을 주고 items가 없으며, 상세에는 품목 2개가 있다.")
        @Test
        void summarizesCustomerList() throws Exception {
            // arrange
            User me = fixture.user();
            Catalog catalog = catalog();
            Order order = fixture.confirmedOrder(me, fixture.item(catalog.a(), 2), fixture.item(catalog.b(), 1));

            // act
            MvcResult list = mockMvc.perform(get(ORDERS).with(customer(me)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult detail = mockMvc.perform(get(ORDER, order.getId()).with(customer(me)))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode summary = findById(data(list).path("content"), order.getId());
            assertAll(
                () -> assertThat(summary.path("itemCount").asInt()).isEqualTo(2),
                () -> assertThat(summary.path("totalAmount").asLong()).isEqualTo(7_000L),
                () -> assertThat(summary.isObject()).isTrue(),
                () -> assertThat(summary.has("items")).isFalse(),
                () -> assertThat(data(detail).path("items").size()).isEqualTo(2)
            );
        }

        @DisplayName("[동등 클래스 분할] 관리자 주문 목록 항목도 품목 수 2와 합계 7,000을 주고 items가 없다.")
        @Test
        void summarizesAdminList() throws Exception {
            // arrange
            User buyer = fixture.user();
            Catalog catalog = catalog();
            Order order = fixture.draftOrder(buyer, fixture.item(catalog.a(), 2), fixture.item(catalog.b(), 1));

            // act
            MvcResult list = mockMvc.perform(get(ADMIN_ORDERS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode summary = findById(data(list).path("content"), order.getId());
            assertAll(
                () -> assertThat(summary.path("itemCount").asInt()).isEqualTo(2),
                () -> assertThat(summary.path("totalAmount").asLong()).isEqualTo(7_000L),
                () -> assertThat(summary.isObject()).isTrue(),
                () -> assertThat(summary.has("items")).isFalse()
            );
        }
    }

    @DisplayName("[P-ORDER-09] 고객과 관리자의 주문 목록은 주문 생성 최신순으로 보여 주고 페이지를 제공한다.")
    @Nested
    class LatestPagedOrders {

        @DisplayName("[동등 클래스 분할] 주문 세 개를 차례로 만들면 고객 목록과 관리자 목록 모두 나중에 만든 주문부터 담는다.")
        @Test
        void listsLatestFirst() throws Exception {
            // arrange
            OrderedOrders orders = orderedOrders();

            // act
            MvcResult mine = mockMvc.perform(get(ORDERS).with(customer(orders.buyer())))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult all = mockMvc.perform(get(ADMIN_ORDERS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(mine).path("content"))).containsExactly(
                    orders.third().getId(), orders.second().getId(), orders.first().getId()),
                () -> assertThat(ids(data(all).path("content"))).containsExactly(
                    orders.third().getId(), orders.second().getId(), orders.first().getId())
            );
        }

        @DisplayName("[경계값 분석] 주문 세 개를 크기 2로 나누면 0페이지에 최신 두 개, 1페이지에 가장 오래된 하나가 있다.")
        @Test
        void splitsIntoPages() throws Exception {
            // arrange
            OrderedOrders orders = orderedOrders();

            // act
            MvcResult page0 = mockMvc.perform(get(ORDERS).param("page", "0").param("size", "2")
                    .with(customer(orders.buyer())))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult page1 = mockMvc.perform(get(ORDERS).param("page", "1").param("size", "2")
                    .with(customer(orders.buyer())))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(page0).path("content")))
                    .containsExactly(orders.third().getId(), orders.second().getId()),
                () -> assertThat(data(page0).path("page").asInt(-1)).isZero(),
                () -> assertThat(data(page0).path("size").asInt()).isEqualTo(2),
                () -> assertThat(data(page0).path("totalElements").asLong()).isEqualTo(3L),
                () -> assertThat(ids(data(page1).path("content"))).containsExactly(orders.first().getId())
            );
        }

        @DisplayName("[동등 클래스 분할] page와 size 없이 조회하면 page 0, size 20으로 응답한다.")
        @Test
        void usesDefaultPage() throws Exception {
            // arrange
            OrderedOrders orders = orderedOrders();

            // act & assert
            mockMvc.perform(get(ORDERS).with(customer(orders.buyer())))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
            mockMvc.perform(get(ADMIN_ORDERS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
        }

        @DisplayName("[경계값 분석] 고객·관리자 주문 목록에서 size 0과 101, page -1은 400 INVALID_REQUEST이다.")
        @ParameterizedTest(name = "page={0}, size={1}")
        @CsvSource({"0, 0", "0, 101", "-1, 20"})
        void rejectsPageOutOfRange(String page, String size) throws Exception {
            // arrange
            User buyer = fixture.user();

            // act & assert
            mockMvc.perform(get(ORDERS).param("page", page).param("size", size).with(customer(buyer)))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
            mockMvc.perform(get(ADMIN_ORDERS).param("page", page).param("size", size).with(admin()))
                .andExpect(failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"));
        }

        private OrderedOrders orderedOrders() {
            User buyer = fixture.user();
            Product product = catalog().a();
            Order first = fixture.draftOrder(buyer, fixture.item(product, 1));
            Order second = fixture.draftOrder(buyer, fixture.item(product, 2));
            Order third = fixture.draftOrder(buyer, fixture.item(product, 3));
            return new OrderedOrders(buyer, first, second, third);
        }
    }

    @DisplayName("[R-ADMIN-10] 관리자는 구매자별 주문 목록과 상세를 조회할 수 있다.")
    @Nested
    class AdminFindsOrders {

        @DisplayName("[동등 클래스 분할] 관리자는 서로 다른 구매자의 주문 상세를 모두 200으로 조회한다.")
        @Test
        void readsEveryBuyersOrder() throws Exception {
            // arrange
            Catalog catalog = catalog();
            Order first = fixture.draftOrder(fixture.user(), fixture.item(catalog.a(), 1));
            Order second = fixture.draftOrder(fixture.user(), fixture.item(catalog.b(), 1));

            // act & assert
            mockMvc.perform(get(ADMIN_ORDER, first.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.id").value(first.getId()));
            mockMvc.perform(get(ADMIN_ORDER, second.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andExpect(jsonPath("$.data.id").value(second.getId()));
        }

        @DisplayName("[동등 클래스 분할] 존재하지 않는 주문을 관리자 상세 조회하면 404 ORDER_NOT_FOUND이다.")
        @Test
        void rejectsMissingOrder() throws Exception {
            mockMvc.perform(get(ADMIN_ORDER, MISSING_ID).with(admin()))
                .andExpect(failure(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
        }
    }

    @DisplayName("[R-ADMIN-11] 관리자는 주문에서 구매자·품목·상태·금액·결제 결과를 확인할 수 있다.")
    @Nested
    class AdminSeesBuyerAndPayment {

        @DisplayName("[상태 전이] 확정된 주문의 관리자 상세에는 구매자와 품목, 합계 7,000, CONFIRMED, 결제액 7,000과 결제 시점이 있다.")
        @Test
        void showsAdminDetail() throws Exception {
            // arrange
            User buyer = fixture.user();
            Catalog catalog = catalog();
            Order order = fixture.confirmedOrder(buyer, fixture.item(catalog.a(), 2), fixture.item(catalog.b(), 1));

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_ORDER, order.getId()).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertThat(data(result).path("buyerId").asLong()).isEqualTo(buyer.getId());
            assertConfirmedDetail(data(result), catalog);
        }

        @DisplayName("[동등 클래스 분할] 관리자 주문 목록 항목에는 구매자, 상태, 합계, 결제액이 있고 확정 전 주문의 결제액은 null이다.")
        @Test
        void showsAdminSummary() throws Exception {
            // arrange
            User buyer = fixture.user();
            Catalog catalog = catalog();
            Order confirmed = fixture.confirmedOrder(buyer, fixture.item(catalog.a(), 2), fixture.item(catalog.b(), 1));
            Order draft = fixture.draftOrder(buyer, fixture.item(catalog.a(), 1));

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_ORDERS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode confirmedSummary = findById(data(result).path("content"), confirmed.getId());
            JsonNode draftSummary = findById(data(result).path("content"), draft.getId());
            assertAll(
                () -> assertThat(confirmedSummary.path("buyerId").asLong()).isEqualTo(buyer.getId()),
                () -> assertThat(confirmedSummary.path("status").asText()).isEqualTo("CONFIRMED"),
                () -> assertThat(confirmedSummary.path("totalAmount").asLong()).isEqualTo(7_000L),
                () -> assertThat(confirmedSummary.path("paymentAmount").asLong()).isEqualTo(7_000L),
                () -> assertThat(draftSummary.path("buyerId").asLong()).isEqualTo(buyer.getId()),
                () -> assertThat(draftSummary.path("status").asText()).isEqualTo("DRAFT"),
                () -> assertThat(draftSummary.path("totalAmount").asLong()).isEqualTo(2_000L),
                () -> assertThat(isNullOrAbsent(draftSummary, "paymentAmount")).isTrue()
            );
        }
    }

    @DisplayName("[P-ADMIN-10] 관리자 주문 목록은 모든 주문을 구매자와 함께 보여 주고, 구매자로 거르는 조건을 함께 제공한다.")
    @Nested
    class BuyerFilter {

        @DisplayName("[의사결정표] buyerId 없이 조회하면 두 구매자의 주문을 모두 담는다.")
        @Test
        void listsAllBuyersWithoutFilter() throws Exception {
            // arrange
            BuyersOrders orders = buyersOrders();

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_ORDERS).with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(result).path("content")))
                    .containsExactlyInAnyOrder(orders.first().getId(), orders.second().getId()),
                () -> assertThat(data(result).path("totalElements").asLong()).isEqualTo(2L)
            );
        }

        @DisplayName("[의사결정표] buyerId로 거르면 그 구매자의 주문만 담는다.")
        @Test
        void filtersByBuyer() throws Exception {
            // arrange
            BuyersOrders orders = buyersOrders();

            // act
            MvcResult result = mockMvc.perform(get(ADMIN_ORDERS)
                    .param("buyerId", String.valueOf(orders.first().getBuyerId()))
                    .with(admin()))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            assertAll(
                () -> assertThat(ids(data(result).path("content"))).containsExactly(orders.first().getId()),
                () -> assertThat(data(result).path("totalElements").asLong()).isEqualTo(1L)
            );
        }

        private BuyersOrders buyersOrders() {
            Catalog catalog = catalog();
            Order first = fixture.draftOrder(fixture.user(), fixture.item(catalog.a(), 1));
            Order second = fixture.draftOrder(fixture.user(), fixture.item(catalog.b(), 1));
            return new BuyersOrders(first, second);
        }
    }

    private Catalog catalog() {
        Brand brand = fixture.brand("Nike");
        Product a = fixture.product(brand, "A", 2_000L, 5);
        Product b = fixture.product(brand, "B", 3_000L, 3);
        return new Catalog(a, b);
    }

    private static String itemsBody(long productId, int quantity) {
        return "{\"items\":[{\"productId\":%d,\"quantity\":%d}]}".formatted(productId, quantity);
    }

    private static String itemsBody(long firstId, int firstQuantity, long secondId, int secondQuantity) {
        return "{\"items\":[{\"productId\":%d,\"quantity\":%d},{\"productId\":%d,\"quantity\":%d}]}"
            .formatted(firstId, firstQuantity, secondId, secondQuantity);
    }

    private MockHttpServletRequestBuilder createOrder(User customer, String requestBody) {
        return post(ORDERS)
            .with(customer(customer)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestBody);
    }

    private MockHttpServletRequestBuilder confirm(User customer, Long orderId) {
        return post(CONFIRM, orderId).with(customer(customer)).with(csrf());
    }

    private void assertNoOrders(User customer) throws Exception {
        mockMvc.perform(get(ORDERS).with(customer(customer)))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.totalElements").value(0));
        mockMvc.perform(get(ADMIN_ORDERS).with(admin()))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    private long balanceOf(User customer) throws Exception {
        MvcResult result = mockMvc.perform(get(POINTS).with(customer(customer)))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.balance").exists())
            .andReturn();
        return data(result).path("balance").asLong();
    }

    private int stockOf(Product product) throws Exception {
        MvcResult result = mockMvc.perform(get(ADMIN_PRODUCT, product.getId()).with(admin()))
            .andExpect(success(HttpStatus.OK))
            .andExpect(jsonPath("$.data.stock").exists())
            .andReturn();
        return data(result).path("stock").asInt();
    }

    private static void assertConfirmedDetail(JsonNode detail, Catalog catalog) {
        JsonNode items = detail.path("items");
        JsonNode itemA = findByProductId(items, catalog.a().getId());
        JsonNode itemB = findByProductId(items, catalog.b().getId());
        assertAll(
            () -> assertThat(detail.path("status").asText()).isEqualTo("CONFIRMED"),
            () -> assertThat(items.size()).isEqualTo(2),
            () -> assertThat(itemA.path("productName").asText()).isEqualTo("A"),
            () -> assertThat(itemA.path("quantity").asInt()).isEqualTo(2),
            () -> assertThat(itemA.path("unitPrice").asLong()).isEqualTo(2_000L),
            () -> assertThat(itemA.path("amount").asLong()).isEqualTo(4_000L),
            () -> assertThat(itemB.path("productName").asText()).isEqualTo("B"),
            () -> assertThat(itemB.path("quantity").asInt()).isEqualTo(1),
            () -> assertThat(itemB.path("unitPrice").asLong()).isEqualTo(3_000L),
            () -> assertThat(itemB.path("amount").asLong()).isEqualTo(3_000L),
            () -> assertThat(detail.path("totalAmount").asLong()).isEqualTo(7_000L),
            () -> assertThat(detail.path("payment").path("amount").asLong()).isEqualTo(7_000L),
            () -> assertThat(detail.path("payment").path("paidAt").asText()).isNotBlank()
        );
    }

    record Catalog(Product a, Product b) {
    }

    record OrderedOrders(User buyer, Order first, Order second, Order third) {
    }

    record BuyersOrders(Order first, Order second) {
    }
}
