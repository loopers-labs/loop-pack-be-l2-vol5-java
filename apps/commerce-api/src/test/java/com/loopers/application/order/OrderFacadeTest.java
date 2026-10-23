package com.loopers.application.order;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.order.dto.OrderItemInfo;
import com.loopers.application.order.dto.OrderSummaryInfo;
import com.loopers.application.order.dto.PaymentInfo;
import com.loopers.application.product.AdminProductFacade;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.ConfirmedOrders;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class OrderFacadeTest {

    private static final long MAX_PRICE = 1_000_000_000L;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private AdminProductFacade adminProductFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private User saveUser() {
        return userRepository.save(User.create());
    }

    private Product saveProduct(String name, long price, int stock) {
        return productRepository.save(Product.create(1L, name, new Money(price), new Stock(stock)));
    }

    private Product saveDeletedProduct(String name) {
        Product product = Product.create(1L, name, new Money(1_000), new Stock(1));
        product.delete();
        return productRepository.save(product);
    }

    private int orderCount() {
        return jdbcTemplate.queryForObject("select count(*) from orders", Integer.class);
    }

    private CoreException rejected(Runnable action) {
        return assertThrows(CoreException.class, action::run);
    }

    @DisplayName("주문을 생성할 때,")
    @Nested
    class Create {
        @DisplayName("DRAFT 주문이 저장되고, 품목·단가·합계가 응답에 담긴다.")
        @Test
        void savesDraftOrder_withItemsAndTotal() {
            // arrange: 4-1 시나리오. 3,000 × 1 + 2,000 × 2 = 7,000
            User user = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product socks = saveProduct("양말", 2_000, 10);

            // act
            OrderInfo info = orderFacade.create(
                user.getId(),
                List.of(new OrderItemCommand(shirt.getId(), 1), new OrderItemCommand(socks.getId(), 2))
            );

            // assert
            assertThat(info.id()).isNotNull();
            assertThat(info.userId()).isEqualTo(user.getId());
            assertThat(info.status()).isEqualTo(OrderStatus.DRAFT);
            assertThat(info.totalAmount()).isEqualTo(7_000L);
            assertThat(info.items()).hasSize(2);
            assertThat(info.items().get(0)).isEqualTo(new OrderItemInfo(shirt.getId(), "티셔츠", 1, 3_000L, 3_000L));
            assertThat(info.items().get(1)).isEqualTo(new OrderItemInfo(socks.getId(), "양말", 2, 2_000L, 4_000L));
            assertThat(orderCount()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                "select status from orders where id = ?", String.class, info.id()
            )).isEqualTo("DRAFT");
        }

        @DisplayName("재고가 요청 수량과 같으면 주문을 만들 수 있고, 재고는 차감하지 않는다.")
        @Test
        void savesDraftOrder_whenStockEqualsQuantity() {
            // arrange
            User user = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000, 5);

            // act
            OrderInfo info = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shirt.getId(), 5)));

            // assert
            assertThat(info.status()).isEqualTo(OrderStatus.DRAFT);
            assertThat(productRepository.findActive(shirt.getId()).orElseThrow().getStock()).isEqualTo(new Stock(5));
        }

        @DisplayName("재고가 0이거나 요청 수량보다 적으면 OUT_OF_STOCK 이고 저장하지 않는다.")
        @Test
        void throwsOutOfStock_whenStockIsSoldOutOrShort() {
            // arrange
            User user = saveUser();
            Product soldOut = saveProduct("품절 상품", 3_000, 0);
            Product few = saveProduct("재고 3개", 3_000, 3);

            // act
            CoreException soldOutException = rejected(
                () -> orderFacade.create(user.getId(), List.of(new OrderItemCommand(soldOut.getId(), 1)))
            );
            CoreException shortException = rejected(
                () -> orderFacade.create(user.getId(), List.of(new OrderItemCommand(few.getId(), 5)))
            );

            // assert
            assertThat(soldOutException.getErrorType()).isEqualTo(ErrorType.OUT_OF_STOCK);
            assertThat(shortException.getErrorType()).isEqualTo(ErrorType.OUT_OF_STOCK);
            assertThat(orderCount()).isZero();
            assertThat(productRepository.findActive(few.getId()).orElseThrow().getStock()).isEqualTo(new Stock(3));
        }

        @DisplayName("재고가 부족한 상품이 여럿이면 메시지에 상품명을 모두 담고, 충분한 상품은 담지 않는다.")
        @Test
        void throwsOutOfStock_withAllShortProductNames() {
            // arrange
            User user = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000, 1);
            Product socks = saveProduct("양말", 2_000, 10);
            Product hat = saveProduct("모자", 5_000, 0);

            // act
            CoreException exception = rejected(() -> orderFacade.create(
                user.getId(),
                List.of(
                    new OrderItemCommand(shirt.getId(), 2),
                    new OrderItemCommand(socks.getId(), 1),
                    new OrderItemCommand(hat.getId(), 1)
                )
            ));

            // assert
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.OUT_OF_STOCK);
            assertThat(exception.getMessage()).contains("재고가 부족합니다.").contains("(티셔츠, 모자)");
            assertThat(exception.getMessage()).doesNotContain("양말");
        }

        @DisplayName("없는 상품과 재고 부족이 함께 있으면 NOT_FOUND 가 먼저이고, 값 규칙 위반과 함께 있으면 INVALID_INPUT 이 먼저다.")
        @Test
        void checksExistenceAndValueRules_beforeStock() {
            // arrange
            User user = saveUser();
            Product soldOut = saveProduct("품절 상품", 3_000, 0);

            // act & assert
            assertThat(rejected(() -> orderFacade.create(
                user.getId(),
                List.of(new OrderItemCommand(soldOut.getId(), 1), new OrderItemCommand(999_999L, 1))
            )).getErrorType()).as("재고 부족 + 없는 상품").isEqualTo(ErrorType.NOT_FOUND);
            assertThat(rejected(() -> orderFacade.create(
                user.getId(),
                List.of(new OrderItemCommand(soldOut.getId(), 1), new OrderItemCommand(soldOut.getId(), 1))
            )).getErrorType()).as("재고 부족 + 중복").isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(orderCount()).isZero();
        }

        @DisplayName("주문 뒤 관리자가 상품 이름·가격을 바꿔도 저장된 품목은 주문 당시 값을 유지한다.")
        @Test
        void keepsSnapshot_whenProductChangesAfterwards() {
            // arrange
            User user = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            OrderInfo info = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shirt.getId(), 2)));

            // act
            adminProductFacade.update(shirt.getId(), "후드티", 50_000);

            // assert: 상품은 바뀌었지만 주문 품목은 그대로다.
            assertThat(productRepository.findActive(shirt.getId()).orElseThrow().getName()).isEqualTo("후드티");
            Map<String, Object> item = jdbcTemplate.queryForMap(
                "select product_name, unit_price, amount from order_items where order_id = ?", info.id()
            );
            assertThat(item.get("product_name")).isEqualTo("티셔츠");
            assertThat(((Number) item.get("unit_price")).longValue()).isEqualTo(3_000L);
            assertThat(((Number) item.get("amount")).longValue()).isEqualTo(6_000L);
            assertThat(jdbcTemplate.queryForObject(
                "select total_amount from orders where id = ?", Long.class, info.id()
            )).isEqualTo(6_000L);
        }

        @DisplayName("없는 상품이나 삭제된 상품이 있으면 NOT_FOUND 이고, 메시지에 해당 상품 id 를 모두 담고, 저장하지 않는다.")
        @Test
        void throwsNotFound_withAllMissingProductIds() {
            // arrange
            User user = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product deleted = saveDeletedProduct("삭제됨");
            long missingId = 999_999L;

            // act
            CoreException exception = rejected(() -> orderFacade.create(
                user.getId(),
                List.of(
                    new OrderItemCommand(deleted.getId(), 1),
                    new OrderItemCommand(shirt.getId(), 1),
                    new OrderItemCommand(missingId, 1)
                )
            ));

            // assert: 요청 순서대로 모든 문제 상품을 알린다.
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(exception.getMessage())
                .contains("존재하지 않는 상품이 포함되어 있습니다.")
                .contains("상품 ID: " + deleted.getId() + ", " + missingId);
            assertThat(orderCount()).isZero();
        }

        @DisplayName("값 규칙을 어기면 INVALID_INPUT 이고 저장하지 않는다.")
        @Test
        void throwsInvalidInput_whenValueRuleIsViolated() {
            // arrange
            User user = saveUser();
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Long productId = shirt.getId();

            // act & assert
            assertThat(rejected(() -> orderFacade.create(user.getId(), List.of())).getErrorType())
                .as("빈 목록").isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(rejected(() -> orderFacade.create(user.getId(), null)).getErrorType())
                .as("목록 없음").isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(rejected(() -> orderFacade.create(
                user.getId(), Arrays.asList(new OrderItemCommand(productId, 1), null)
            )).getErrorType()).as("null 품목").isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(rejected(() -> orderFacade.create(user.getId(), List.of(new OrderItemCommand(productId, 0))))
                .getErrorType()).as("수량 0").isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(rejected(() -> orderFacade.create(user.getId(), List.of(new OrderItemCommand(productId, -1))))
                .getErrorType()).as("수량 음수").isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(rejected(() -> orderFacade.create(
                user.getId(), List.of(new OrderItemCommand(productId, 1), new OrderItemCommand(productId, 2))
            )).getErrorType()).as("중복 상품").isEqualTo(ErrorType.INVALID_INPUT);
            assertThat(orderCount()).isZero();
        }

    }

    @DisplayName("내 주문을 조회할 때,")
    @Nested
    class Query {
        private User saveUserWithBalance(long amount) {
            User user = User.create();
            user.chargePoint(new Money(amount));
            return userRepository.save(user);
        }

        private Long placeOrder(User user, OrderItemCommand... items) {
            return orderFacade.create(user.getId(), List.of(items)).id();
        }

        @DisplayName("생성한 주문의 응답에는 주문 시각이 담기고, 확정돼도 같은 주문 시각으로 조회된다.")
        @Test
        void includesOrderedAt_andKeepsItAfterConfirmation() {
            // arrange
            User user = saveUserWithBalance(10_000);
            Product shirt = saveProduct("티셔츠", 3_000, 10);

            // act
            OrderInfo created = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shirt.getId(), 1)));
            ConfirmedOrders.confirm(jdbcTemplate, created.id());
            OrderInfo confirmed = orderFacade.getMine(user.getId(), created.id());

            // assert
            assertThat(created.orderedAt()).isNotNull();
            assertThat(confirmed.orderedAt()).isEqualTo(created.orderedAt());
        }

        @DisplayName("목록은 내 주문만 최신순으로 요약(품목 수, 결제 결과, 주문 시각)해서 돌려준다.")
        @Test
        void returnsMyOrdersSummary_newestFirst() {
            // arrange
            User me = saveUserWithBalance(10_000);
            User other = saveUserWithBalance(10_000);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Product socks = saveProduct("양말", 2_000, 10);
            Long draftId = placeOrder(me, new OrderItemCommand(shirt.getId(), 1));
            placeOrder(other, new OrderItemCommand(shirt.getId(), 1));
            Long confirmedId = placeOrder(me, new OrderItemCommand(shirt.getId(), 1), new OrderItemCommand(socks.getId(), 2));
            ConfirmedOrders.confirm(jdbcTemplate, confirmedId);

            // act
            Page<OrderSummaryInfo> page = orderFacade.getMyList(me.getId(), 0, 20);

            // assert
            assertThat(page.getContent()).extracting(OrderSummaryInfo::id).containsExactly(confirmedId, draftId);
            OrderSummaryInfo confirmed = page.getContent().get(0);
            assertThat(confirmed.userId()).isEqualTo(me.getId());
            assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(confirmed.totalAmount()).isEqualTo(7_000L);
            assertThat(confirmed.itemCount()).isEqualTo(2);
            assertThat(confirmed.payment()).isEqualTo(new PaymentInfo(7_000L));
            assertThat(confirmed.orderedAt()).isNotNull();
            OrderSummaryInfo draft = page.getContent().get(1);
            assertThat(draft.status()).isEqualTo(OrderStatus.DRAFT);
            assertThat(draft.itemCount()).isEqualTo(1);
            assertThat(draft.payment()).isNull();
        }

        @DisplayName("상세는 품목·결제 결과·주문 시각을 돌려주고, 다른 사용자의 주문과 없는 주문은 NOT_FOUND 이다.")
        @Test
        void returnsMyOrderDetail_orThrowsNotFound() {
            // arrange
            User me = saveUserWithBalance(10_000);
            User other = saveUserWithBalance(10_000);
            Product shirt = saveProduct("티셔츠", 3_000, 10);
            Long orderId = placeOrder(me, new OrderItemCommand(shirt.getId(), 2));
            ConfirmedOrders.confirm(jdbcTemplate, orderId);

            // act
            OrderInfo info = orderFacade.getMine(me.getId(), orderId);
            CoreException otherUsers = rejected(() -> orderFacade.getMine(other.getId(), orderId));
            CoreException missing = rejected(() -> orderFacade.getMine(me.getId(), 999_999L));

            // assert
            assertThat(info.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(info.items()).containsExactly(new OrderItemInfo(shirt.getId(), "티셔츠", 2, 3_000L, 6_000L));
            assertThat(info.payment()).isEqualTo(new PaymentInfo(6_000L));
            assertThat(info.orderedAt()).isNotNull();
            assertThat(otherUsers.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
            assertThat(missing.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

    }
}
