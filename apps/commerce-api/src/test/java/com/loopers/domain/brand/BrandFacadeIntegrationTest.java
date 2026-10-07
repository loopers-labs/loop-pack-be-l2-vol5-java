package com.loopers.domain.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderConfirmFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointChangeCause;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductQueryResult;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductSort;
import com.loopers.domain.product.StockChangeCause;
import com.loopers.domain.user.UserModel;
import com.loopers.fixture.BrandFixture;
import com.loopers.fixture.ProductFixture;
import com.loopers.fixture.UserFixture;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.like.LikeJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.point.PointHistoryJpaRepository;
import com.loopers.infrastructure.point.PointJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockHistoryJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 브랜드 일괄 삭제의 실제 bulk SQL 과 바인딩 값을 실행 근거로 남기기 위해
 * 이 테스트 클래스에만 Hibernate 바인딩 로그를 켠다.
 */
@DisplayName("BrandFacade 는 브랜드를 등록·조회·수정·삭제한다.")
@SpringBootTest(properties = "logging.level.org.hibernate.orm.jdbc.bind=TRACE")
class BrandFacadeIntegrationTest {

    @Autowired
    private BrandFacade brandFacade;
    @Autowired
    private BrandFixture brandFixture;
    @Autowired
    private ProductFixture productFixture;
    @Autowired
    private UserFixture userFixture;
    @Autowired
    private ProductFacade productFacade;
    @Autowired
    private LikeFacade likeFacade;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderConfirmFacade orderConfirmFacade;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private BrandJpaRepository brandJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private LikeJpaRepository likeJpaRepository;
    @Autowired
    private StockHistoryJpaRepository stockHistoryJpaRepository;
    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private PointJpaRepository pointJpaRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 활성 조건 없이 새 트랜잭션에서 읽은 상품 행의 상태. */
    private record ProductRow(
        Long brandId, String name, long price, long stock,
        ZonedDateTime createdAt, ZonedDateTime updatedAt, ZonedDateTime deletedAt
    ) {
        ProductRow withoutModifiedTimes() {
            return new ProductRow(brandId, name, price, stock, createdAt, null, null);
        }
    }

    private ProductRow productRow(Long productId) {
        ProductModel product = productJpaRepository.findById(productId).orElseThrow();
        return new ProductRow(product.getBrandId(), product.getName(), product.getPrice().toWon(),
            product.getStockQuantity(), product.getCreatedAt(), product.getUpdatedAt(), product.getDeletedAt());
    }

    private List<List<Object>> likeRows() {
        return likeJpaRepository.findAll().stream()
            .map(like -> List.<Object>of(like.getId(), like.getUserId(), like.getProductId()))
            .toList();
    }

    private List<List<Object>> stockHistoryRows() {
        return stockHistoryJpaRepository.findAll().stream()
            .map(history -> Arrays.asList((Object) history.getId(), history.getProductId(),
                history.getBeforeQuantity(), history.getAfterQuantity(), history.getCause(), history.getOrderId()))
            .toList();
    }

    private List<List<Object>> pointHistoryRows() {
        return pointHistoryJpaRepository.findAll().stream()
            .map(history -> Arrays.asList((Object) history.getId(), history.getPointId(),
                history.getBeforeBalance(), history.getAfterBalance(), history.getCause(), history.getOrderId()))
            .toList();
    }

    /** 주문의 상태·금액·결제 결과와 저장된 품목 스냅샷. */
    private List<Object> orderRow(Long orderId) {
        OrderModel order = orderJpaRepository.findWithItemsById(orderId).orElseThrow();
        List<List<Object>> items = order.getItems().stream()
            .map(item -> List.<Object>of(item.getProductId(), item.getQuantity(), item.getUnitPrice().toWon()))
            .toList();
        return Arrays.asList(order.getStatus(), order.getOrderTotal().toWon(), order.getUsedPointAmount(),
            order.getPaymentAmount() != null ? order.getPaymentAmount().toWon() : null, items);
    }

    @DisplayName("등록")
    @Nested
    class Create {
        @DisplayName("앞뒤 공백을 제거한 이름으로 저장한다.")
        @Test
        void savesWithTrimmedName() {
            BrandModel created = brandFacade.create("  나이키  ");

            assertAll(
                () -> assertThat(created.getId()).isNotNull(),
                () -> assertThat(created.getName()).isEqualTo("나이키"),
                () -> assertThat(brandJpaRepository.findById(created.getId()).orElseThrow().getName()).isEqualTo("나이키")
            );
        }

        @DisplayName("이름이 비어 있으면 INVALID_BRAND_NAME 으로 거절하고 저장하지 않는다.")
        @Test
        void rejectsBlankName() {
            assertThatThrownBy(() -> brandFacade.create("   "))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.INVALID_BRAND_NAME);
            assertThat(brandJpaRepository.findAll()).isEmpty();
        }
    }

    @DisplayName("상세 조회")
    @Nested
    class GetBrand {
        @DisplayName("삭제되지 않은 브랜드를 반환한다.")
        @Test
        void returnsActiveBrand() {
            BrandModel nike = brandFixture.createBrand("나이키");

            BrandModel found = brandFacade.getBrand(nike.getId());

            assertThat(found.getName()).isEqualTo("나이키");
        }

        @DisplayName("존재하지 않는 브랜드는 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsUnknownBrand() {
            assertThatThrownBy(() -> brandFacade.getBrand(999L))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
        }

        @DisplayName("삭제된 브랜드는 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedBrand() {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            assertThatThrownBy(() -> brandFacade.getBrand(deleted.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
        }
    }

    @DisplayName("수정")
    @Nested
    class Update {
        @DisplayName("이름을 변경해 저장한다.")
        @Test
        void changesName() {
            BrandModel nike = brandFixture.createBrand("나이키");

            BrandModel updated = brandFacade.update(nike.getId(), "나이키 코리아");

            assertAll(
                () -> assertThat(updated.getName()).isEqualTo("나이키 코리아"),
                () -> assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getName())
                    .isEqualTo("나이키 코리아")
            );
        }

        @DisplayName("잘못된 이름으로는 기존 이름을 바꾸지 않는다.")
        @Test
        void keepsNameWhenInvalid() {
            BrandModel nike = brandFixture.createBrand("나이키");

            assertThatThrownBy(() -> brandFacade.update(nike.getId(), ""))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.INVALID_BRAND_NAME);
            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getName()).isEqualTo("나이키");
        }

        @DisplayName("삭제된 브랜드는 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedBrand() {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");

            assertThatThrownBy(() -> brandFacade.update(deleted.getId(), "새이름"))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
        }
    }
    @DisplayName("삭제")
    @Nested
    class Delete {
        @DisplayName("재고 0 을 포함한 연결 미삭제 상품 전체와 브랜드를 삭제하고, 상품은 삭제·수정 시각만 같은 값으로 바꾼다.")
        @Test
        void deletesBrandWithAllActiveProducts() {
            BrandModel nike = brandFixture.createBrand("나이키");
            BrandModel adidas = brandFixture.createBrand("아디다스");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 10_000L, 3L);
            ProductModel soldOut = productFixture.createProduct(nike.getId(), "품절 운동화", 20_000L, 0L);
            ProductModel discontinued = productFixture.createProduct(nike.getId(), "단종 운동화", 30_000L, 5L);
            productFixture.deleteProduct(discontinued.getId());
            ProductModel slipper = productFixture.createProduct(adidas.getId(), "삼선 슬리퍼", 5_000L, 4L);

            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 50_000L);
            productFacade.changeStock(shoes.getId(), 5L);
            likeFacade.like(user.getId(), shoes.getId());
            likeFacade.like(user.getId(), slipper.getId());
            OrderModel pastOrder = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shoes.getId(), 2L),
                new OrderItemCommand(slipper.getId(), 1L)
            ));
            orderConfirmFacade.confirm(user.getId(), pastOrder.getId());

            ProductRow shoesBefore = productRow(shoes.getId());
            ProductRow soldOutBefore = productRow(soldOut.getId());
            ProductRow discontinuedBefore = productRow(discontinued.getId());
            ProductRow slipperBefore = productRow(slipper.getId());
            List<List<Object>> likesBefore = likeRows();
            List<List<Object>> stockHistoriesBefore = stockHistoryRows();
            List<List<Object>> pointHistoriesBefore = pointHistoryRows();
            List<Object> orderBefore = orderRow(pastOrder.getId());
            ZonedDateTime requestedAt = ZonedDateTime.now().truncatedTo(ChronoUnit.SECONDS);

            brandFacade.delete(nike.getId());

            ZonedDateTime finishedAt = ZonedDateTime.now().plusSeconds(1);
            ProductRow shoesAfter = productRow(shoes.getId());
            ProductRow soldOutAfter = productRow(soldOut.getId());
            assertAll(
                () -> assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull(),
                () -> assertThat(brandJpaRepository.findById(adidas.getId()).orElseThrow().getDeletedAt()).isNull(),
                () -> assertThat(shoesAfter.deletedAt()).isNotNull(),
                () -> assertThat(shoesAfter.deletedAt()).isBetween(requestedAt, finishedAt),
                () -> assertThat(shoesAfter.updatedAt()).isEqualTo(shoesAfter.deletedAt()),
                () -> assertThat(soldOutAfter.deletedAt()).isEqualTo(shoesAfter.deletedAt()),
                () -> assertThat(soldOutAfter.updatedAt()).isEqualTo(shoesAfter.deletedAt()),
                () -> assertThat(shoesAfter.withoutModifiedTimes()).isEqualTo(shoesBefore.withoutModifiedTimes()),
                () -> assertThat(soldOutAfter.withoutModifiedTimes()).isEqualTo(soldOutBefore.withoutModifiedTimes()),
                () -> assertThat(productRow(discontinued.getId())).isEqualTo(discontinuedBefore),
                () -> assertThat(productRow(slipper.getId())).isEqualTo(slipperBefore),
                () -> assertThat(likeRows()).isEqualTo(likesBefore),
                () -> assertThat(stockHistoryRows()).isEqualTo(stockHistoriesBefore),
                () -> assertThat(pointHistoryRows()).isEqualTo(pointHistoriesBefore),
                () -> assertThat(orderRow(pastOrder.getId())).isEqualTo(orderBefore)
            );
        }

        @DisplayName("연결된 활성 상품이 없으면 삭제 시각을 기록한다.")
        @Test
        void deletesWhenNoActiveProducts() {
            BrandModel nike = brandFixture.createBrand("나이키");

            brandFacade.delete(nike.getId());

            assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull();
        }

        @DisplayName("연결된 상품이 모두 삭제되었다면 삭제하고 기존 상품 행은 바꾸지 않는다.")
        @Test
        void deletesWhenAllProductsDeleted() {
            BrandModel nike = brandFixture.createBrand("나이키");
            var shoes = productFixture.createProduct(nike.getId(), "운동화", 10_000L, 3L);
            productFixture.deleteProduct(shoes.getId());
            ProductRow shoesBefore = productRow(shoes.getId());

            brandFacade.delete(nike.getId());

            assertAll(
                () -> assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull(),
                () -> assertThat(productRow(shoes.getId())).isEqualTo(shoesBefore)
            );
        }

        @DisplayName("다른 브랜드의 활성 상품은 삭제를 막지 않고 바뀌지도 않는다.")
        @Test
        void ignoresOtherBrandProducts() {
            BrandModel nike = brandFixture.createBrand("나이키");
            BrandModel adidas = brandFixture.createBrand("아디다스");
            var slipper = productFixture.createProduct(adidas.getId(), "삼선 슬리퍼", 20_000L, 5L);
            ProductRow slipperBefore = productRow(slipper.getId());

            brandFacade.delete(nike.getId());

            assertAll(
                () -> assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNotNull(),
                () -> assertThat(productRow(slipper.getId())).isEqualTo(slipperBefore)
            );
        }

        @DisplayName("존재하지 않는 브랜드는 BRAND_NOT_FOUND 로 거절하고 상품을 바꾸지 않는다.")
        @Test
        void rejectsUnknownBrand() {
            BrandModel adidas = brandFixture.createBrand("아디다스");
            var slipper = productFixture.createProduct(adidas.getId(), "삼선 슬리퍼", 20_000L, 5L);
            ProductRow slipperBefore = productRow(slipper.getId());

            assertThatThrownBy(() -> brandFacade.delete(adidas.getId() + 999L))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
            assertThat(productRow(slipper.getId())).isEqualTo(slipperBefore);
        }

        @DisplayName("이미 삭제된 브랜드를 다시 삭제하면 상품 변경 전에 BRAND_NOT_FOUND 로 거절한다.")
        @Test
        void rejectsDeletedBrand() {
            BrandModel deleted = brandFixture.createDeletedBrand("사라진브랜드");
            var leftover = productFixture.createProduct(deleted.getId(), "남은 운동화", 10_000L, 2L);
            ProductRow leftoverBefore = productRow(leftover.getId());
            ZonedDateTime brandDeletedAt = brandJpaRepository.findById(deleted.getId()).orElseThrow().getDeletedAt();

            assertThatThrownBy(() -> brandFacade.delete(deleted.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.BRAND_NOT_FOUND);
            assertAll(
                () -> assertThat(productRow(leftover.getId())).isEqualTo(leftoverBefore),
                () -> assertThat(brandJpaRepository.findById(deleted.getId()).orElseThrow().getDeletedAt())
                    .isEqualTo(brandDeletedAt)
            );
        }
    }

    @DisplayName("브랜드별 bulk soft delete 와 영속성 컨텍스트")
    @Nested
    class BulkSoftDelete {
        private final ZonedDateTime deletedAt =
            ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000, ZoneId.of("Asia/Seoul"));

        @DisplayName("해당 브랜드의 미삭제 상품만 전달한 시각으로 삭제·수정 시각을 바꾸고 변경 행 수를 반환한다.")
        @Test
        void updatesOnlyActiveProductsOfBrand() {
            BrandModel nike = brandFixture.createBrand("나이키");
            BrandModel adidas = brandFixture.createBrand("아디다스");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 10_000L, 3L);
            ProductModel soldOut = productFixture.createProduct(nike.getId(), "품절 운동화", 20_000L, 0L);
            ProductModel discontinued = productFixture.createProduct(nike.getId(), "단종 운동화", 30_000L, 5L);
            productFixture.deleteProduct(discontinued.getId());
            ProductModel slipper = productFixture.createProduct(adidas.getId(), "삼선 슬리퍼", 5_000L, 4L);
            ProductRow shoesBefore = productRow(shoes.getId());
            ProductRow soldOutBefore = productRow(soldOut.getId());
            ProductRow discontinuedBefore = productRow(discontinued.getId());
            ProductRow slipperBefore = productRow(slipper.getId());

            Integer changed = transactionTemplate.execute(
                status -> productRepository.softDeleteAllActiveByBrandId(nike.getId(), deletedAt));

            ProductRow shoesAfter = productRow(shoes.getId());
            ProductRow soldOutAfter = productRow(soldOut.getId());
            assertAll(
                () -> assertThat(changed).isEqualTo(2),
                () -> assertThat(shoesAfter.deletedAt().toInstant()).isEqualTo(deletedAt.toInstant()),
                () -> assertThat(shoesAfter.updatedAt().toInstant()).isEqualTo(deletedAt.toInstant()),
                () -> assertThat(soldOutAfter.deletedAt().toInstant()).isEqualTo(deletedAt.toInstant()),
                () -> assertThat(soldOutAfter.updatedAt().toInstant()).isEqualTo(deletedAt.toInstant()),
                () -> assertThat(shoesAfter.withoutModifiedTimes()).isEqualTo(shoesBefore.withoutModifiedTimes()),
                () -> assertThat(soldOutAfter.withoutModifiedTimes()).isEqualTo(soldOutBefore.withoutModifiedTimes()),
                () -> assertThat(productRow(discontinued.getId())).isEqualTo(discontinuedBefore),
                () -> assertThat(productRow(slipper.getId())).isEqualTo(slipperBefore),
                () -> assertThat(brandJpaRepository.findById(nike.getId()).orElseThrow().getDeletedAt()).isNull()
            );
        }

        @DisplayName("미삭제 상품이 없으면 0 을 반환하고 아무 행도 바꾸지 않는다.")
        @Test
        void returnsZeroWithoutActiveProducts() {
            BrandModel nike = brandFixture.createBrand("나이키");
            ProductModel discontinued = productFixture.createProduct(nike.getId(), "단종 운동화", 30_000L, 5L);
            productFixture.deleteProduct(discontinued.getId());
            ProductRow discontinuedBefore = productRow(discontinued.getId());

            Integer changed = transactionTemplate.execute(
                status -> productRepository.softDeleteAllActiveByBrandId(nike.getId(), deletedAt));

            assertAll(
                () -> assertThat(changed).isZero(),
                () -> assertThat(productRow(discontinued.getId())).isEqualTo(discontinuedBefore)
            );
        }

        @DisplayName("bulk 실행 후에도 영속성 컨텍스트를 자동으로 비우지 않으며, 이미 적재한 상품 객체는 갱신되지 않는다.")
        @Test
        void doesNotClearPersistenceContext() {
            BrandModel nike = brandFixture.createBrand("나이키");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 10_000L, 3L);

            List<Object> observed = transactionTemplate.execute(status -> {
                BrandModel managedBrand = brandRepository.findActive(nike.getId()).orElseThrow();
                ProductModel loadedProduct = productRepository.findActive(shoes.getId()).orElseThrow();

                productRepository.softDeleteAllActiveByBrandId(nike.getId(), deletedAt);

                return Arrays.asList(entityManager.contains(managedBrand),
                    entityManager.contains(loadedProduct), loadedProduct.getDeletedAt());
            });

            assertAll(
                () -> assertThat(observed.get(0)).isEqualTo(true),
                () -> assertThat(observed.get(1)).isEqualTo(true),
                () -> assertThat(observed.get(2)).isNull(),
                () -> assertThat(productRow(shoes.getId()).deletedAt().toInstant()).isEqualTo(deletedAt.toInstant())
            );
        }

        @DisplayName("product 테이블에는 brand_id 단독 인덱스가 하나만 있다.")
        @Test
        void hasSingleBrandIdIndex() {
            Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
            jdbcTemplate.query("""
                    SELECT INDEX_NAME, COLUMN_NAME
                      FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product'
                     ORDER BY INDEX_NAME, SEQ_IN_INDEX
                    """,
                (rs) -> {
                    columnsByIndex.computeIfAbsent(rs.getString("INDEX_NAME"), name -> new ArrayList<>())
                        .add(rs.getString("COLUMN_NAME"));
                });
            // 실행 근거 기록용: 테스트 DB 버전과 새 연결의 격리 수준.
            System.out.println("[B-T3] product indexes = " + columnsByIndex + ", db = "
                + jdbcTemplate.queryForMap("SELECT VERSION() AS version, @@transaction_isolation AS isolation"));

            assertAll(
                () -> assertThat(columnsByIndex.values()).filteredOn(columns -> columns.equals(List.of("brand_id")))
                    .hasSize(1),
                () -> assertThat(columnsByIndex.values()).filteredOn(columns -> columns.get(0).equals("brand_id"))
                    .hasSize(1)
            );
        }
    }

    @DisplayName("브랜드 일괄 삭제 이후 사용 제한과 보존")
    @Nested
    class AfterBrandRemoval {
        private final PageCommand firstPage = PageCommand.of(null, null);

        /** 일괄 삭제 대상 브랜드(재고 있는 상품·재고 0 상품), 다른 브랜드 상품, 좋아요와 과거 확정 주문을 준비한 뒤 브랜드를 삭제한다. */
        private record RemovedBrand(
            UserModel user, ProductModel shoes, ProductModel soldOut, ProductModel slipper,
            OrderModel pastOrder, List<Object> pastOrderBeforeRemoval
        ) {
        }

        private RemovedBrand removeBrandWithProducts() {
            BrandModel nike = brandFixture.createBrand("나이키");
            BrandModel adidas = brandFixture.createBrand("아디다스");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 10_000L, 3L);
            ProductModel soldOut = productFixture.createProduct(nike.getId(), "품절 운동화", 20_000L, 0L);
            ProductModel slipper = productFixture.createProduct(adidas.getId(), "삼선 슬리퍼", 5_000L, 4L);
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 50_000L);
            likeFacade.like(user.getId(), shoes.getId());
            likeFacade.like(user.getId(), slipper.getId());
            OrderModel pastOrder = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 1L)));
            orderConfirmFacade.confirm(user.getId(), pastOrder.getId());
            List<Object> pastOrderBeforeRemoval = orderRow(pastOrder.getId());

            brandFacade.delete(nike.getId());

            return new RemovedBrand(user, shoes, soldOut, slipper, pastOrder, pastOrderBeforeRemoval);
        }

        private List<Long> idsOf(PageResult<ProductQueryResult> page) {
            return page.items().stream().map(ProductQueryResult::id).toList();
        }

        private void assertProductNotFound(ThrowingCallable call) {
            assertThatThrownBy(call)
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.PRODUCT_NOT_FOUND);
        }

        @DisplayName("고객·관리자 상품 목록·상세·전체 개수와 내 좋아요 목록에서 일괄 삭제된 상품을 제외한다.")
        @Test
        void hidesRemovedProductsFromQueries() {
            RemovedBrand removed = removeBrandWithProducts();
            Long userId = removed.user().getId();
            Long removedBrandId = removed.shoes().getBrandId();

            PageResult<ProductQueryResult> customerPage = productFacade.getProducts(null, firstPage, ProductSort.LATEST);
            PageResult<ProductQueryResult> removedBrandPage =
                productFacade.getProducts(removedBrandId, firstPage, ProductSort.LATEST);
            PageResult<ProductQueryResult> adminPage = productFacade.getAllProducts(firstPage, ListSort.LATEST);
            PageResult<ProductQueryResult> likedPage = likeFacade.getMyLikedProducts(userId, firstPage, ListSort.LATEST);

            assertAll(
                () -> assertThat(idsOf(customerPage)).containsExactly(removed.slipper().getId()),
                () -> assertThat(customerPage.totalElements()).isEqualTo(1L),
                () -> assertThat(idsOf(removedBrandPage)).isEmpty(),
                () -> assertThat(removedBrandPage.totalElements()).isZero(),
                () -> assertThat(idsOf(adminPage)).containsExactly(removed.slipper().getId()),
                () -> assertThat(adminPage.totalElements()).isEqualTo(1L),
                () -> assertThat(idsOf(likedPage)).containsExactly(removed.slipper().getId()),
                () -> assertThat(likedPage.totalElements()).isEqualTo(1L),
                () -> assertProductNotFound(() -> productFacade.getProduct(removed.shoes().getId())),
                () -> assertProductNotFound(() -> productFacade.getProduct(removed.soldOut().getId()))
            );
        }

        @DisplayName("일괄 삭제된 상품에 대한 새 좋아요·새 주문·수정·재고 변경·재삭제를 PRODUCT_NOT_FOUND 로 거절하고 상태를 바꾸지 않는다.")
        @Test
        void rejectsNewUseOfRemovedProducts() {
            RemovedBrand removed = removeBrandWithProducts();
            Long userId = removed.user().getId();
            Long shoesId = removed.shoes().getId();
            Long soldOutId = removed.soldOut().getId();
            ProductRow shoesBefore = productRow(shoesId);
            ProductRow soldOutBefore = productRow(soldOutId);
            List<List<Object>> likesBefore = likeRows();
            List<List<Object>> stockHistoriesBefore = stockHistoryRows();
            long ordersBefore = orderJpaRepository.count();

            assertAll(
                () -> assertProductNotFound(() -> likeFacade.like(userId, soldOutId)),
                () -> assertProductNotFound(() -> orderFacade.create(userId, List.of(new OrderItemCommand(shoesId, 1L)))),
                () -> assertProductNotFound(() -> productFacade.update(shoesId, "새 운동화", 12_000L)),
                () -> assertProductNotFound(() -> productFacade.changeStock(shoesId, 10L)),
                () -> assertProductNotFound(() -> productFacade.delete(soldOutId))
            );
            assertAll(
                () -> assertThat(productRow(shoesId)).isEqualTo(shoesBefore),
                () -> assertThat(productRow(soldOutId)).isEqualTo(soldOutBefore),
                () -> assertThat(likeRows()).isEqualTo(likesBefore),
                () -> assertThat(stockHistoryRows()).isEqualTo(stockHistoriesBefore),
                () -> assertThat(orderJpaRepository.count()).isEqualTo(ordersBefore)
            );
        }

        @DisplayName("삭제 전에 만든 자신의 좋아요는 취소할 수 있고 과거 주문은 저장된 품목·금액·결제 결과 그대로 조회된다.")
        @Test
        void keepsExistingLikeCancelAndPastOrders() {
            RemovedBrand removed = removeBrandWithProducts();
            Long userId = removed.user().getId();
            Long pastOrderId = removed.pastOrder().getId();

            likeFacade.cancel(userId, removed.shoes().getId());

            OrderModel customerView = orderFacade.getOrder(userId, pastOrderId);
            OrderModel adminView = orderFacade.getAnyOrder(pastOrderId);
            assertAll(
                () -> assertThat(likeJpaRepository.findByUserIdAndProductId(userId, removed.shoes().getId())).isEmpty(),
                () -> assertThat(likeJpaRepository.findByUserIdAndProductId(userId, removed.slipper().getId())).isPresent(),
                () -> assertThat(orderRow(pastOrderId)).isEqualTo(removed.pastOrderBeforeRemoval()),
                () -> assertThat(customerView.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(customerView.getOrderTotal().toWon()).isEqualTo(10_000L),
                () -> assertThat(customerView.getPaymentAmount().toWon()).isEqualTo(10_000L),
                () -> assertThat(customerView.getItems()).singleElement()
                    .satisfies(item -> {
                        assertThat(item.getProductId()).isEqualTo(removed.shoes().getId());
                        assertThat(item.getQuantity()).isEqualTo(1L);
                        assertThat(item.getUnitPrice().toWon()).isEqualTo(10_000L);
                    }),
                () -> assertThat(adminView.getId()).isEqualTo(pastOrderId),
                () -> assertThat(adminView.getPaymentAmount().toWon()).isEqualTo(10_000L)
            );
        }

        @DisplayName("브랜드 삭제가 commit 된 뒤 삭제 전에 만든 DRAFT 를 충분한 포인트로 확정하면 PRODUCT_NOT_FOUND 로 거절하고 아무것도 차감하지 않는다.")
        @Test
        void rejectsConfirmingDraftCreatedBeforeRemoval() {
            BrandModel nike = brandFixture.createBrand("나이키");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 10_000L, 5L);
            UserModel user = userFixture.createUserWithPoint();
            pointFacade.charge(user.getId(), 50_000L);
            OrderModel draft = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 2L)));
            List<Object> draftBefore = orderRow(draft.getId());
            List<List<Object>> pointHistoriesBefore = pointHistoryRows();
            List<List<Object>> stockHistoriesBefore = stockHistoryRows();
            brandFacade.delete(nike.getId());

            assertThatThrownBy(() -> orderConfirmFacade.confirm(user.getId(), draft.getId()))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.PRODUCT_NOT_FOUND);

            assertAll(
                () -> assertThat(orderRow(draft.getId())).isEqualTo(draftBefore),
                () -> assertThat(draftBefore).containsExactly(OrderStatus.DRAFT, 20_000L, null, null,
                    List.of(List.of(shoes.getId(), 2L, 10_000L))),
                () -> assertThat(pointJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance())
                    .isEqualTo(50_000L),
                () -> assertThat(productRow(shoes.getId()).stock()).isEqualTo(5L),
                () -> assertThat(pointHistoryRows()).isEqualTo(pointHistoriesBefore),
                () -> assertThat(stockHistoryRows()).isEqualTo(stockHistoriesBefore),
                () -> assertThat(pointHistoryJpaRepository.findAll())
                    .noneMatch(history -> history.getCause() == PointChangeCause.ORDER_USE),
                () -> assertThat(stockHistoryJpaRepository.findAll())
                    .noneMatch(history -> history.getCause() == StockChangeCause.ORDER_DEDUCTION)
            );
        }
    }

    @DisplayName("목록 조회")
    @Nested
    class GetBrands {
        @DisplayName("삭제된 브랜드를 제외하고 latest 는 생성 시각 내림차순으로 반환한다.")
        @Test
        void returnsActiveBrandsLatestFirst() {
            brandFixture.createBrand("첫째");
            brandFixture.createBrand("둘째");
            brandFixture.createDeletedBrand("삭제됨");
            brandFixture.createBrand("셋째");

            PageResult<BrandModel> result = brandFacade.getBrands(PageCommand.of(null, null), ListSort.LATEST);

            assertAll(
                () -> assertThat(result.totalElements()).isEqualTo(3L),
                () -> assertThat(result.items()).extracting(BrandModel::getName)
                    .containsExactly("셋째", "둘째", "첫째")
            );
        }

        @DisplayName("oldest 는 생성 시각 오름차순으로 반환한다.")
        @Test
        void returnsOldestFirst() {
            brandFixture.createBrand("첫째");
            brandFixture.createBrand("둘째");
            brandFixture.createBrand("셋째");

            PageResult<BrandModel> result = brandFacade.getBrands(PageCommand.of(null, null), ListSort.OLDEST);

            assertThat(result.items()).extracting(BrandModel::getName).containsExactly("첫째", "둘째", "셋째");
        }

        @DisplayName("요청한 페이지 크기만큼 끊어 전체 개수와 함께 반환한다.")
        @Test
        void returnsRequestedPage() {
            List.of("첫째", "둘째", "셋째", "넷째", "다섯째").forEach(brandFixture::createBrand);

            PageResult<BrandModel> result = brandFacade.getBrands(PageCommand.of(1, 2), ListSort.OLDEST);

            assertAll(
                () -> assertThat(result.page()).isEqualTo(1),
                () -> assertThat(result.size()).isEqualTo(2),
                () -> assertThat(result.totalElements()).isEqualTo(5L),
                () -> assertThat(result.totalPages()).isEqualTo(3),
                () -> assertThat(result.items()).extracting(BrandModel::getName).containsExactly("셋째", "넷째")
            );
        }
    }
}
