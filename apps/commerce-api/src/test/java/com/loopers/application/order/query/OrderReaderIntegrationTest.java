package com.loopers.application.order.query;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderReaderIntegrationTest {

    @Autowired
    private OrderReader orderReader;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private UserModel admin;
    private BrandModel brand;
    private ProductModel product;

    @BeforeEach
    void setUp() {
        user = fixtures.userWithBalance(10_000L);
        admin = fixtures.admin();
        brand = fixtures.brand("브랜드");
        product = fixtures.product(brand.getId(), "상품", 1_000L, 5);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Nested
    @DisplayName("FR-ORDER-03 내 주문 목록")
    class ListMyOrders {
        @DisplayName("[FR-ORDER-03] 요청자의 DRAFT·CONFIRMED 주문 전부, 최신순, 품목·결제액 포함. 남의 주문 제외.")
        @Test
        void listsMyOrders() {
            UserModel other = fixtures.userWithBalance(10_000L);
            OrderModel draft = fixtures.draftOrder(user.getId(), product, 1);
            OrderModel confirmed = fixtures.confirmedOrder(user.getId(), product, 2);
            fixtures.draftOrder(other.getId(), product, 1);

            PageResult<OrderView.Detail> page = orderReader.listMyOrders(user.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(2);
            assertThat(page.items()).extracting(OrderView.Detail::id).containsExactly(confirmed.getId(), draft.getId());
            assertThat(page.items().get(0).paidAmount()).isEqualTo(2_000L);
            assertThat(page.items().get(1).paidAmount()).isNull();
            assertThat(page.items().get(0).items()).hasSize(1);
        }

        @DisplayName("[FR-ORDER-03 INVALID_PAGE]")
        @Test
        void throwsInvalidPage() {
            assertThrowsErrorType(() -> orderReader.listMyOrders(user.getId(), PageQuery.of(-1, 10)), ErrorType.INVALID_PAGE);
        }
    }

    @Nested
    @DisplayName("FR-ORDER-04 내 주문 상세")
    class GetMyOrder {
        @DisplayName("[FR-ORDER-04] 품목·수량·금액·상태·결제액을 돌려준다.")
        @Test
        void returnsOrder() {
            OrderModel order = fixtures.draftOrder(user.getId(), product, 3);

            OrderView.Detail info = orderReader.getMyOrder(user.getId(), order.getId());

            assertThat(info.items().get(0).quantity()).isEqualTo(3);
            assertThat(info.totalAmount()).isEqualTo(3_000L);
            assertThat(info.status()).isEqualTo("DRAFT");
        }

        @DisplayName("[FR-ORDER-04 ORDER_NOT_FOUND]")
        @Test
        void throwsOrderNotFound() {
            assertThrowsErrorType(() -> orderReader.getMyOrder(user.getId(), 999L), ErrorType.ORDER_NOT_FOUND);
        }

        @DisplayName("[FR-ORDER-04 NOT_OWNER][ASM-09] 남의 주문은 NOT_OWNER 로 노출한다 (DR-18).")
        @Test
        void throwsNotOwner() {
            UserModel other = fixtures.userWithBalance(0L);
            OrderModel order = fixtures.draftOrder(other.getId(), product, 1);

            assertThrowsErrorType(() -> orderReader.getMyOrder(user.getId(), order.getId()), ErrorType.NOT_OWNER);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-ORDER-01 주문 목록 (관리자)")
    class ListOrdersForAdmin {
        @DisplayName("[FR-ADMIN-ORDER-01][ASM-19] 구매자별 묶음, 묶음 순서는 사용자 ID 오름차순, 묶음 안은 최신순. DRAFT·CONFIRMED 모두.")
        @Test
        void groupsByBuyer() {
            UserModel other = fixtures.userWithBalance(10_000L);
            OrderModel userDraft = fixtures.draftOrder(user.getId(), product, 1);
            OrderModel otherOrder = fixtures.draftOrder(other.getId(), product, 1);
            OrderModel userConfirmed = fixtures.confirmedOrder(user.getId(), product, 1);
            fixtures.userWithBalance(0L); // 주문 없는 사용자는 묶음에 없다

            PageResult<OrderView.BuyerGroup> page = orderReader.listOrdersForAdmin(admin.getId(), PageQuery.of(0, 10));

            assertThat(page.totalCount()).isEqualTo(2);
            assertThat(page.items()).extracting(OrderView.BuyerGroup::userId).containsExactly(user.getId(), other.getId());
            assertThat(page.items().get(0).orders()).extracting(OrderView.Detail::id).containsExactly(userConfirmed.getId(), userDraft.getId());
            assertThat(page.items().get(1).orders()).extracting(OrderView.Detail::id).containsExactly(otherOrder.getId());
            assertThat(page.items().get(0).orders().get(0).userId()).isEqualTo(user.getId());
        }

        @DisplayName("[FR-ADMIN-ORDER-01][ASM-20] 페이지 단위는 주문이 아니라 구매자 묶음이다.")
        @Test
        void pagesByBuyerGroup() {
            UserModel other = fixtures.userWithBalance(10_000L);
            fixtures.draftOrder(user.getId(), product, 1);
            fixtures.draftOrder(user.getId(), product, 1);
            fixtures.draftOrder(user.getId(), product, 1);
            fixtures.draftOrder(other.getId(), product, 1);

            PageResult<OrderView.BuyerGroup> first = orderReader.listOrdersForAdmin(admin.getId(), PageQuery.of(0, 1));
            PageResult<OrderView.BuyerGroup> second = orderReader.listOrdersForAdmin(admin.getId(), PageQuery.of(1, 1));

            assertThat(first.totalCount()).isEqualTo(2);
            assertThat(first.items()).hasSize(1);
            assertThat(first.items().get(0).userId()).isEqualTo(user.getId());
            assertThat(first.items().get(0).orders()).hasSize(3);
            assertThat(second.items().get(0).userId()).isEqualTo(other.getId());
        }

        @DisplayName("[FR-ADMIN-ORDER-01 INVALID_PAGE] / [NOT_ADMIN]")
        @Test
        void throwsInvalidPage_andNotAdmin() {
            assertThrowsErrorType(() -> orderReader.listOrdersForAdmin(admin.getId(), PageQuery.of(0, 0)), ErrorType.INVALID_PAGE);
            assertThrowsErrorType(() -> orderReader.listOrdersForAdmin(user.getId(), PageQuery.of(0, 10)), ErrorType.NOT_ADMIN);
        }
    }

    @Nested
    @DisplayName("FR-ADMIN-ORDER-02 주문 상세 (관리자)")
    class GetOrderForAdmin {
        @DisplayName("[FR-ADMIN-ORDER-02] 구매자·품목·단가·항목 금액·합계·상태·결제액·결제 결과. 남의 주문도 조회된다.")
        @Test
        void returnsAnyOrder() {
            OrderModel order = fixtures.confirmedOrder(user.getId(), product, 2);

            OrderView.Detail info = orderReader.getOrderForAdmin(admin.getId(), order.getId());

            assertThat(info.userId()).isEqualTo(user.getId());
            assertThat(info.items().get(0).lineAmount()).isEqualTo(2_000L);
            assertThat(info.paidAmount()).isEqualTo(2_000L);
            assertThat(info.confirmedAt()).isNotNull();
        }

        @DisplayName("[FR-ADMIN-ORDER-02 ORDER_NOT_FOUND]")
        @Test
        void throwsOrderNotFound() {
            assertThrowsErrorType(() -> orderReader.getOrderForAdmin(admin.getId(), 999L), ErrorType.ORDER_NOT_FOUND);
        }
    }
}
