package com.loopers.application.brand;

import com.loopers.application.order.OrderFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductErrorCode;
import com.loopers.domain.product.ProductErrorDetail;
import com.loopers.support.error.CoreException;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * 브랜드 일괄 삭제의 트랜잭션 경계를 실제 DB 로 확인함 (BRD-02, 3주차 설계 2.2, 6.3).
 * 테스트 메서드를 트랜잭션으로 감싸지 않고, 서비스 트랜잭션이 끝난 뒤 새 트랜잭션에서 다시 읽음
 */
@SpringBootTest
class BrandDeleteTransactionTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private OrderFacade orderFacade;

    @MockitoSpyBean
    private ProductService productService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Brand brand;
    private Product inStock;
    private Product soldOut;
    private Product otherBrands;
    private Order pastOrder;

    @BeforeEach
    void setUp() {
        brand = persist(new Brand("브랜드", null));
        Brand other = persist(new Brand("다른 브랜드", null));
        inStock = persistProduct(brand, "재고 있음", 5);
        soldOut = persistProduct(brand, "재고 0", 0);
        otherBrands = persistProduct(other, "다른 브랜드 상품", 5);
        pastOrder = persistConfirmedOrder(inStock);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("브랜드를 삭제하면, 그 브랜드와 연결 상품(재고 0 포함)이 함께 삭제되고 다른 브랜드 상품과 과거 주문은 그대로다.")
    @Test
    void deletesBrandAndItsProducts() {
        // act
        brandFacade.deleteBrand(brand.getId());

        // assert
        assertAll(
            () -> assertThat(isDeleted("brand", brand.getId())).isTrue(),
            () -> assertThat(isDeleted("product", inStock.getId())).isTrue(),
            () -> assertThat(isDeleted("product", soldOut.getId())).isTrue(),
            () -> assertThat(isDeleted("product", otherBrands.getId())).isFalse(),
            () -> assertThat(pastOrderSnapshot()).isEqualTo(List.of("CONFIRMED", 5_000L, "재고 있음", 1_000L, 5))
        );
    }

    @DisplayName("브랜드 삭제 전에 만든 DRAFT 주문을 확정하면, 상품 삭제 여부를 다시 확인해 PRODUCT_NOT_FOUND 로 거절하고 DRAFT 로 남는다. (ORD-09)")
    @Test
    void rejectsConfirmingDraftCreatedBeforeBrandDeletion() {
        // arrange
        Order draft = persist(Order.draft(USER_ID, List.of(new OrderLine(soldOut.getId(), soldOut.getName(), soldOut.getPrice(), 1)),
            ZonedDateTime.now()));
        Point point = new Point(USER_ID);
        point.charge(10_000L);
        persist(point);
        brandFacade.deleteBrand(brand.getId());

        // act
        CoreException result = assertThrows(CoreException.class, () -> orderFacade.confirmOrder(USER_ID, draft.getId()));

        // assert
        assertAll(
            () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
            () -> assertThat(result.getDetail()).isEqualTo(ProductErrorDetail.of(soldOut.getId())),
            () -> assertThat(orderStatusOf(draft.getId())).isEqualTo("DRAFT")
        );
    }

    @DisplayName("상품 삭제 SQL 과 브랜드 삭제 SQL 이 나간 뒤 실패하면, 브랜드와 연결 상품 모두 삭제 전 상태로 남는다.")
    @Test
    void rollsBackBrandAndProducts_whenFailsAfterChangesAreFlushed() {
        // arrange: 마지막 단계(상품 일괄 삭제)를 실제로 실행하고, 브랜드 변경까지 DB 로 보낸 뒤 실패시킴
        AtomicReference<List<Boolean>> seenInsideTransaction = new AtomicReference<>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            seenInsideTransaction.set(List.of(
                isDeletedInCurrentTransaction("brand", brand.getId()),
                isDeletedInCurrentTransaction("product", inStock.getId()),
                isDeletedInCurrentTransaction("product", soldOut.getId())
            ));
            throw new IllegalStateException("테스트에서 주입한 실패");
        }).when(productService).deleteAllOfBrand(anyLong());

        // act
        assertThrows(IllegalStateException.class, () -> brandFacade.deleteBrand(brand.getId()));

        // assert
        assertAll(
            () -> assertThat(seenInsideTransaction.get()).containsExactly(true, true, true),
            () -> assertThat(isDeleted("brand", brand.getId())).isFalse(),
            () -> assertThat(isDeleted("product", inStock.getId())).isFalse(),
            () -> assertThat(isDeleted("product", soldOut.getId())).isFalse(),
            () -> assertThat(isDeleted("product", otherBrands.getId())).isFalse(),
            () -> assertThat(pastOrderSnapshot()).isEqualTo(List.of("CONFIRMED", 5_000L, "재고 있음", 1_000L, 5))
        );
    }

    private <T> T persist(T entity) {
        return transactionTemplate.execute(status -> {
            entityManager.persist(entity);
            return entity;
        });
    }

    private Product persistProduct(Brand owner, String name, int stock) {
        return transactionTemplate.execute(status -> {
            Product product = new Product(entityManager.find(Brand.class, owner.getId()), name, 1_000L);
            product.changeStock(stock);
            entityManager.persist(product);
            return product;
        });
    }

    private Order persistConfirmedOrder(Product product) {
        ZonedDateTime now = ZonedDateTime.now();
        Order order = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), 5)), now);
        order.confirm(order.getTotalAmount(), now);
        return persist(order);
    }

    /** 서비스 트랜잭션이 끝난 뒤 새 트랜잭션에서 DB 를 다시 읽음 */
    private boolean isDeleted(String table, Long id) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> isDeletedInCurrentTransaction(table, id)));
    }

    private boolean isDeletedInCurrentTransaction(String table, Long id) {
        Object deletedAt = entityManager.createNativeQuery("select deleted_at from " + table + " where id = :id")
            .setParameter("id", id)
            .getSingleResult();
        return deletedAt != null;
    }

    private String orderStatusOf(Long orderId) {
        return transactionTemplate.execute(status -> (String) entityManager.createNativeQuery("select status from orders where id = :id")
            .setParameter("id", orderId)
            .getSingleResult());
    }

    /** 과거 주문의 상태 · 결제액과 품목의 스냅샷(상품명 · 단가 · 수량) */
    private List<Object> pastOrderSnapshot() {
        return transactionTemplate.execute(status -> {
            Object[] row = (Object[]) entityManager.createNativeQuery(
                    "select o.status, o.payment_amount, i.product_name, i.unit_price, i.quantity "
                        + "from orders o join order_items i on i.order_id = o.id where o.id = :id")
                .setParameter("id", pastOrder.getId())
                .getSingleResult();
            return List.of(
                row[0],
                ((Number) row[1]).longValue(),
                row[2],
                ((Number) row[3]).longValue(),
                ((Number) row[4]).intValue()
            );
        });
    }
}
