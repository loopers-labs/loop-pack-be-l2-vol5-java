package com.loopers.domain.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.payment.PaymentFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.concurrency.ConcurrentRunner;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.pg.PaymentTestSupport;
import com.loopers.support.pg.TestPgResetExtension;
import com.loopers.support.pg.TestPgServer;
import com.loopers.support.pg.TestPgStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

// 캐시 키가 쓰는 revision을, 결과를 바꾸는 쓰기만 같은 트랜잭션에서 올리는지 본다.
// 목록 revision은 (최신순, 가격순, 좋아요순) 순서로 비교한다
@SpringBootTest
@ExtendWith(TestPgResetExtension.class)
class ProductCacheRevisionIntegrationTest {

    private static final long USER_ID = 1L;

    private final TestPgServer pg = TestPgServer.shared();

    @Autowired
    private ProductListRevisionService productListRevisionService;

    @Autowired
    private ProductRevisionService productRevisionService;

    @Autowired
    private ProductFacade productFacade;

    @Autowired
    private BrandFacade brandFacade;

    @Autowired
    private LikeFacade likeFacade;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentFacade paymentFacade;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Long brandId;
    private Long otherBrandId;
    private Long productId;
    private Long otherBrandProductId;

    @BeforeEach
    void setUp() {
        brandId = brandJpaRepository.save(new BrandModel("나이키")).getId();
        otherBrandId = brandJpaRepository.save(new BrandModel("아디다스")).getId();
        productId = productJpaRepository.save(new ProductModel(brandId, "runner", 10_000L, 5)).getId();
        otherBrandProductId = productJpaRepository.save(new ProductModel(otherBrandId, "slipper", 5_000L, 5)).getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품을 바꾸면, ")
    @Nested
    class ProductChange {
        @DisplayName("등록은 그 브랜드의 모든 정렬 목록 revision만 올린다.")
        @Test
        void increasesAllSortRevisions_whenCreated() {
            // act
            productFacade.createProduct(brandId, "new", 1_000L, 1);

            // assert
            assertAll(
                () -> assertThat(listRevisions(brandId)).containsExactly(1L, 1L, 1L),
                () -> assertThat(listRevisions(otherBrandId)).containsExactly(0L, 0L, 0L)
            );
        }

        @DisplayName("가격 수정은 상품 revision과 가격순 목록 revision만 올린다.")
        @Test
        void increasesProductAndPriceRevisions_whenPriceChanges() {
            // act
            productFacade.updateProduct(productId, "runner", 12_000L);

            // assert
            assertAll(
                () -> assertThat(productRevision(productId)).isEqualTo(1L),
                () -> assertThat(listRevisions(brandId)).containsExactly(0L, 1L, 0L)
            );
        }

        @DisplayName("이름만 수정하면, 목록 순서는 그대로라 상품 revision만 올린다.")
        @Test
        void increasesOnlyProductRevision_whenOnlyNameChanges() {
            // act
            productFacade.updateProduct(productId, "runner v2", 10_000L);

            // assert
            assertAll(
                () -> assertThat(productRevision(productId)).isEqualTo(1L),
                () -> assertThat(listRevisions(brandId)).containsExactly(0L, 0L, 0L)
            );
        }

        @DisplayName("재고 변경은 상품 revision만 올린다.")
        @Test
        void increasesOnlyProductRevision_whenStockChanges() {
            // act
            productFacade.changeStock(productId, 10);

            // assert
            assertAll(
                () -> assertThat(productRevision(productId)).isEqualTo(1L),
                () -> assertThat(listRevisions(brandId)).containsExactly(0L, 0L, 0L)
            );
        }

        @DisplayName("삭제는 상품 revision과 그 브랜드의 모든 정렬 목록 revision을 올린다.")
        @Test
        void increasesProductAndAllSortRevisions_whenDeleted() {
            // act
            productFacade.deleteProduct(productId);

            // assert
            assertAll(
                () -> assertThat(productRevision(productId)).isEqualTo(1L),
                () -> assertThat(listRevisions(brandId)).containsExactly(1L, 1L, 1L)
            );
        }
    }

    @DisplayName("브랜드를 바꾸면, ")
    @Nested
    class BrandChange {
        @DisplayName("이름 변경은 캐시에 브랜드 이름을 담지 않으므로 어떤 revision도 올리지 않는다.")
        @Test
        void keepsRevisions_whenRenamed() {
            // act
            brandFacade.updateBrand(brandId, "NIKE");

            // assert
            assertAll(
                () -> assertThat(productRevision(productId)).isZero(),
                () -> assertThat(listRevisions(brandId)).containsExactly(0L, 0L, 0L)
            );
        }

        @DisplayName("삭제는 그 브랜드의 모든 정렬 목록 revision을 올린다.")
        @Test
        void increasesAllSortRevisions_whenDeleted() {
            // act
            brandFacade.deleteBrand(brandId);

            // assert
            assertAll(
                () -> assertThat(listRevisions(brandId)).containsExactly(1L, 1L, 1L),
                () -> assertThat(listRevisions(otherBrandId)).containsExactly(0L, 0L, 0L)
            );
        }
    }

    @DisplayName("좋아요를 바꾸면, ")
    @Nested
    class LikeChange {
        @DisplayName("등록과 취소마다 상품 revision과 좋아요순 목록 revision이 오르고, 다른 정렬은 그대로다.")
        @Test
        void increasesProductAndLikesRevisions() {
            // act & assert
            likeFacade.like(USER_ID, productId);
            assertThat(productRevision(productId)).isEqualTo(1L);
            assertThat(listRevisions(brandId)).containsExactly(0L, 0L, 1L);

            likeFacade.unlike(USER_ID, productId);
            assertThat(productRevision(productId)).isEqualTo(2L);
            assertThat(listRevisions(brandId)).containsExactly(0L, 0L, 2L);
        }

        @DisplayName("같은 브랜드의 다른 상품 좋아요는 상품 id에 따라 다른 칸의 행을 쓰고, 목록 revision은 칸을 모두 더한 값이다.")
        @Test
        void spreadsLikesOverSlots_andSumsThem() {
            // arrange — id가 이어진 상품은 서로 다른 칸에 들어간다
            List<Long> productIds = saveProducts(3);

            // act
            productIds.forEach(id -> likeFacade.like(USER_ID, id));

            // assert
            assertAll(
                () -> assertThat(revisionRowsOf(brandId)).isEqualTo(3L),
                () -> assertThat(listRevisions(brandId)).containsExactly(0L, 0L, 3L)
            );
        }

        @DisplayName("같은 브랜드의 여러 상품에 좋아요가 동시에 몰려도, 갱신 유실 없이 목록 revision이 좋아요 수만큼 오른다.")
        @Test
        void increasesLikesRevisionWithoutLostUpdates_whenLikesConcentrateOnBrand() {
            // arrange — 칸 수보다 상품이 많아 같은 칸을 함께 쓰는 좋아요도 생긴다
            List<Long> productIds = saveProducts(16);
            List<Callable<Void>> likes = productIds.stream()
                .map(id -> (Callable<Void>) () -> {
                    likeFacade.like(USER_ID, id);
                    return null;
                })
                .toList();

            // act
            List<ConcurrentRunner.WorkerResult<Void>> results = ConcurrentRunner.runTogether(likes, Duration.ofSeconds(30));

            // assert
            assertAll(
                () -> assertThat(results).extracting(ConcurrentRunner.WorkerResult::outcome)
                    .containsOnly(ConcurrentRunner.Outcome.SUCCESS),
                () -> assertThat(listRevisions(brandId)).containsExactly(0L, 0L, 16L),
                () -> assertThat(productIds).allSatisfy(id -> assertThat(productRevision(id)).isEqualTo(1L))
            );
        }

        @DisplayName("삭제된 상품의 좋아요를 취소하면, 목록에 나가지 않는 상품이라 revision을 올리지 않는다.")
        @Test
        void keepsRevisions_whenProductIsDeleted() {
            // arrange
            likeFacade.like(USER_ID, productId);
            productFacade.deleteProduct(productId);
            long productBefore = productRevision(productId);
            List<Long> listBefore = listRevisions(brandId);

            // act
            likeFacade.unlike(USER_ID, productId);

            // assert
            assertAll(
                () -> assertThat(productRevision(productId)).isEqualTo(productBefore),
                () -> assertThat(listRevisions(brandId)).isEqualTo(listBefore)
            );
        }
    }

    @DisplayName("주문의 PG 결제를 진행하면, ")
    @Nested
    class OrderPayment {
        @DisplayName("결제를 시작할 때 재고를 확보한 상품의 revision만 오르고, 목록 순서는 그대로라 목록 revision은 그대로다.")
        @Test
        void increasesOnlyItemProductRevisions_whenPaymentStarts() {
            // arrange
            Long orderId = saveDraftOrder();

            // act
            PaymentTestSupport.startPayment(paymentFacade, orderId, USER_ID);

            // assert
            assertAll(
                () -> assertThat(productRevision(productId)).isEqualTo(1L),
                () -> assertThat(productRevision(otherBrandProductId)).isEqualTo(1L),
                () -> assertThat(listRevisions(brandId)).containsExactly(0L, 0L, 0L),
                () -> assertThat(listRevisions(otherBrandId)).containsExactly(0L, 0L, 0L)
            );
        }

        @DisplayName("결제 성공을 확인해도 재고가 바뀌지 않아 revision은 오르지 않는다.")
        @Test
        void keepsRevisions_whenPaymentSucceeds() {
            // arrange
            Long orderId = saveDraftOrder();
            PaymentTestSupport.startPayment(paymentFacade, orderId, USER_ID);
            PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.SUCCESS);

            // act
            paymentFacade.reconcile(orderId, USER_ID);

            // assert
            assertAll(
                () -> assertThat(productRevision(productId)).isEqualTo(1L),
                () -> assertThat(productRevision(otherBrandProductId)).isEqualTo(1L)
            );
        }

        @DisplayName("확정 실패로 재고를 되돌리면 revision이 한 번 더 오르고, 같은 실패 확인이 다시 와도 더 오르지 않는다.")
        @Test
        void increasesRevisionOnce_whenPaymentFailsAndStockIsReturned() {
            // arrange
            Long orderId = saveDraftOrder();
            PaymentTestSupport.startPayment(paymentFacade, orderId, USER_ID);
            PaymentTestSupport.completeAt(pg, orderId, TestPgStatus.FAILED);

            // act
            paymentFacade.reconcile(orderId, USER_ID);
            paymentFacade.reconcile(orderId, USER_ID);

            // assert
            assertAll(
                () -> assertThat(productRevision(productId)).isEqualTo(2L),
                () -> assertThat(productRevision(otherBrandProductId)).isEqualTo(2L),
                () -> assertThat(listRevisions(brandId)).containsExactly(0L, 0L, 0L)
            );
        }

        @DisplayName("재고 부족으로 결제 시작이 롤백되면, 상품 revision도 그대로다.")
        @Test
        void keepsRevisions_whenPaymentStartIsRolledBack() {
            // arrange: 두 번째 품목의 재고(5)보다 많이 주문해 TX A가 재고 확보 도중에 실패하게 한다
            Long orderId = orderJpaRepository.save(new OrderModel(USER_ID, List.of(
                new OrderItem(productId, 1, 10_000L),
                new OrderItem(otherBrandProductId, 6, 5_000L)
            ))).getId();

            // act
            assertThrows(CoreException.class, () -> PaymentTestSupport.startPayment(paymentFacade, orderId, USER_ID));

            // assert: 첫 품목의 재고 차감까지 함께 되돌아간다
            assertAll(
                () -> assertThat(productRevision(productId)).isZero(),
                () -> assertThat(productRevision(otherBrandProductId)).isZero(),
                () -> assertThat(productJpaRepository.findById(productId).orElseThrow().getStock()).isEqualTo(5)
            );
        }
    }

    @DisplayName("브랜드를 지정하지 않은 전체 목록의 revision은, 어느 브랜드의 목록이 바뀌어도 커진다.")
    @Test
    void increasesAllBrandsRevision_whenAnyBrandChanges() {
        // arrange
        long before = productListRevisionService.getRevision(null, ProductSortType.PRICE_ASC);

        // act
        productFacade.updateProduct(otherBrandProductId, "slipper", 6_000L);

        // assert
        assertThat(productListRevisionService.getRevision(null, ProductSortType.PRICE_ASC)).isGreaterThan(before);
    }

    @DisplayName("쓰기 트랜잭션 밖에서 revision을 올리려 하면, 변경과 같은 트랜잭션이 아니므로 거절한다.")
    @Test
    void rejectsIncrease_withoutTransaction() {
        // act & assert
        assertAll(
            () -> assertThrows(IllegalTransactionStateException.class, () -> productRevisionService.increase(productId)),
            () -> assertThrows(IllegalTransactionStateException.class, () -> productListRevisionService.increaseForPrice(brandId))
        );
    }

    private List<Long> listRevisions(Long brandId) {
        return List.of(
            productListRevisionService.getRevision(brandId, ProductSortType.LATEST),
            productListRevisionService.getRevision(brandId, ProductSortType.PRICE_ASC),
            productListRevisionService.getRevision(brandId, ProductSortType.LIKES_DESC)
        );
    }

    private long productRevision(Long productId) {
        return productRevisionService.getRevisions(List.of(productId)).get(productId);
    }

    private long revisionRowsOf(Long brandId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM product_list_revision WHERE brand_id = ?", Long.class, brandId);
    }

    private List<Long> saveProducts(int count) {
        List<Long> productIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            productIds.add(productJpaRepository.save(new ProductModel(brandId, "product-" + i, 1_000L, 1)).getId());
        }
        return productIds;
    }

    private Long saveDraftOrder() {
        OrderModel draft = new OrderModel(USER_ID, List.of(
            new OrderItem(productId, 1, 10_000L),
            new OrderItem(otherBrandProductId, 1, 5_000L)
        ));
        return orderJpaRepository.save(draft).getId();
    }
}
