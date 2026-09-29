package com.loopers.infrastructure.product;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import jakarta.persistence.EntityManager;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductRepositoryIntegrationTest {

    private final ProductService productService;
    private final PointFacade pointFacade;
    private final OrderFacade orderFacade;
    private final ProductRepository productRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final EntityManager entityManager;

    private Long brandId;

    @Autowired
    ProductRepositoryIntegrationTest(
        ProductService productService,
        PointFacade pointFacade,
        OrderFacade orderFacade,
        ProductRepository productRepository,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        EntityManager entityManager
    ) {
        this.productService = productService;
        this.pointFacade = pointFacade;
        this.orderFacade = orderFacade;
        this.productRepository = productRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.entityManager = entityManager;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("등록한 상품이 저장되고, 다시 읽어도 같은 값이다.")
    @Test
    void persistsAcrossReads() {
        Long id = productFacade.register(brandId, "코트", Price.of(129_000)).getId();

        Product found = productService.get(id);
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getBrandId()).isEqualTo(brandId);
        assertThat(found.getName()).isEqualTo("코트");
        assertThat(found.getPrice()).isEqualTo(Price.of(129_000));
    }

    @DisplayName("등록하면 재고 행이 0으로 함께 생긴다. 상품만 있고 재고가 없는 상태는 없다.")
    @Test
    void opensStockRow() {
        Long id = productFacade.register(brandId, "코트", Price.of(129_000)).getId();

        assertThat(productService.getStock(id)).isEqualTo(Quantity.ZERO);
    }

    @DisplayName("4.3 · 저장소의 기본은 살아 있는 것뿐이다. 삭제된 행은 이름으로 밝혀야 보인다.")
    @Test
    void repositoryHidesDeletedByDefault() {
        Long id = productFacade.register(brandId, "코트", Price.of(129_000)).getId();
        productFacade.delete(id);

        assertThat(productRepository.findById(id))
            .as("기본 조회는 삭제된 것을 주지 않는다 — 호출부가 거르기를 잊을 수 없다")
            .isEmpty();
        assertThatThrownBy(() -> productFacade.adjustStock(id, Quantity.of(5)))
            .as("findByIdForUpdate 도 삭제된 상품을 주지 않는다")
            .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);
        assertThat(deletedRowCount(id))
            .as("논리 삭제는 행을 지우지 않는다")
            .isEqualTo(1L);
        assertThatThrownBy(() -> productService.get(id)).isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);
    }

    @DisplayName("재고 변경은 최종 수량을 설정하고, 다시 읽어도 같은 값이다.")
    @Test
    void persistsStock() {
        Long id = productFacade.register(brandId, "코트", Price.of(129_000)).getId();

        productFacade.adjustStock(id, Quantity.of(30));
        productFacade.adjustStock(id, Quantity.of(12));

        assertThat(productService.getStock(id)).isEqualTo(Quantity.of(12));
    }

    @DisplayName("PRODUCT-023 · 재고 10에 20건의 주문 확정이 동시에 들어오면 10건만 성공하고 재고는 0이다.")
    @Test
    void deductsConcurrentlyWithoutGoingNegative() throws InterruptedException {
        Long id = productFacade.register(brandId, "코트", Price.of(1_000)).getId();
        productFacade.adjustStock(id, Quantity.of(10));

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            Long userId = 1_000L + i;
            pointFacade.charge(userId, ChargeAmount.of(10_000), Instant.parse("2026-09-14T00:00:00Z"));
            Long orderId = orderFacade.place(
                new OrderCreateCommand(userId, List.of(new OrderCreateCommand.Line(id, OrderQuantity.of(1)))),
                Instant.parse("2026-09-14T00:00:00Z")).getId();

            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderFacade.confirm(userId, orderId, Instant.parse("2026-09-14T00:00:00Z"));
                    succeeded.incrementAndGet();
                } catch (DomainException e) {
                    if (e.error() == DomainError.INSUFFICIENT_STOCK) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(10);
        assertThat(productService.getStock(id)).isEqualTo(Quantity.ZERO);
    }

    @DisplayName("PRODUCT-006 · 삭제된 상품은 확정으로도 차감되지 않는다.")
    @Test
    void rejectsDeductOfDeleted() {
        Long id = productFacade.register(brandId, "코트", Price.of(1_000)).getId();
        productFacade.adjustStock(id, Quantity.of(5));
        Instant now = Instant.parse("2026-09-14T00:00:00Z");
        pointFacade.charge(1L, ChargeAmount.of(10_000), now);
        Long orderId = orderFacade.place(
            new OrderCreateCommand(1L, List.of(new OrderCreateCommand.Line(id, OrderQuantity.of(1)))), now).getId();
        productFacade.delete(id);

        assertThatThrownBy(() -> orderFacade.confirm(1L, orderId, now))
            .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);
        assertThat(quantityOf(id))
            .as("확정이 거절되었으므로 재고는 그대로다")
            .isEqualTo(5);
    }

    private long deletedRowCount(Long id) {
        return ((Number) entityManager
            .createNativeQuery("SELECT COUNT(*) FROM product WHERE id = :id AND deleted_at IS NOT NULL")
            .setParameter("id", id)
            .getSingleResult()).longValue();
    }

    private int quantityOf(Long id) {
        return ((Number) entityManager
            .createNativeQuery("SELECT quantity FROM product WHERE id = :id")
            .setParameter("id", id)
            .getSingleResult()).intValue();
    }
}
