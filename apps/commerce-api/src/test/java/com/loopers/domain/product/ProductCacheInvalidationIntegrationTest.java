package com.loopers.domain.product;

import org.springframework.test.context.ContextConfiguration;
import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.fixture.PgFixtureContextInitializer;
import com.loopers.fixture.PgCommands;
import com.loopers.application.order.OrderInfo;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.order.OrderItemCommand;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.testsupport.RecordingOrderEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ContextConfiguration(initializers = PgFixtureContextInitializer.class)
@DisplayName("상품 상세 기본정보에 영향을 주는 쓰기는 commit 뒤 해당 기본정보를 삭제하고, 좋아요 쓰기는 캐시를 건드리지 않는다.")
class ProductCacheInvalidationIntegrationTest extends ProductCacheTestSupport {

    @TestBean(name = "kafkaOrderEventPublisher", methodName = RecordingOrderEventPublisher.FACTORY, enforceOverride = true)
    private OrderEventPublisher orderEventPublisher;

    @org.springframework.beans.factory.annotation.Autowired
    private com.loopers.fixture.pg.PgFixtureServer pg;

    private static final PageCommand FIRST_PAGE = PageCommand.of(null, null);

    private UserModel userWithPoint(long amount) {
        UserModel user = userFixture.createUserWithPoint();
        pointFacade.charge(user.getId(), amount);
        return user;
    }

    private OrderInfo confirmedOrder(UserModel user, Long productId, long quantity) {
        OrderInfo order = orderFacade.create(user.getId(), List.of(new OrderItemCommand(productId, quantity)));
        pg.enqueuePlan(com.loopers.fixture.pg.PgOperation.CREATE,
            com.loopers.domain.payment.PaymentModel.providerOrderIdOf(order.id()),
            com.loopers.fixture.pg.ResponsePlan.outcome(com.loopers.fixture.pg.CreateOutcome.SUCCESS));
        OrderInfo confirmed = orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id())).order();
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        return confirmed;
    }

    /** 과거 주문의 총액과 품목 단가를 새 조회로 읽는다. */
    private List<Long> orderAmounts(Long orderId) {
        OrderModel order = orderJpaRepository.findWithItemsById(orderId).orElseThrow();
        OrderItemModel item = order.getItems().get(0);
        return List.of(order.getTotalAmount().toWon(), item.getUnitPrice().toWon(), item.getQuantity());
    }

    private void assertNotFound(Long productId) {
        assertThatThrownBy(() -> productFacade.getProduct(productId))
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.PRODUCT_NOT_FOUND);
    }

    @DisplayName("관리자 가격 수정")
    @Nested
    class PriceChange {
        @DisplayName("commit 뒤 기본정보가 삭제돼 새 가격을 반환하고, 가격순 목록이 바뀌며 과거 주문 금액은 유지한다.")
        @Test
        void returnsNewPriceAndKeepsPastOrder() {
            BrandModel brand = brandFixture.createBrand("나이키");
            ProductModel shoes = productFixture.createProduct(brand.getId(), "운동화", 1_000L, 10L);
            ProductModel cap = productFixture.createProduct(brand.getId(), "모자", 2_000L, 10L);
            UserModel user = userWithPoint(10_000L);
            OrderInfo pastOrder = confirmedOrder(user, shoes.getId(), 2L);
            assertThat(productFacade.getProduct(shoes.getId()).price()).isEqualTo(1_000L);
            assertThat(hasKey(shoes.getId())).isTrue();

            ProductQueryResult adminResponse = productFacade.update(shoes.getId(), "운동화", 3_000L);

            boolean keyAfterCommit = hasKey(shoes.getId());
            ProductQueryResult detail = productFacade.getProduct(shoes.getId());
            List<Long> listOrder = productFacade.getProducts(brand.getId(), FIRST_PAGE, ProductSort.PRICE_ASC)
                .items().stream().map(ProductQueryResult::id).toList();

            assertAll(
                () -> assertThat(adminResponse.price()).isEqualTo(3_000L),
                () -> assertThat(keyAfterCommit).isFalse(),
                () -> assertThat(detail.price()).isEqualTo(3_000L),
                () -> assertThat(detail.stockQuantity()).isEqualTo(8L),
                () -> assertThat(rawValue(shoes.getId())).contains("\"price\":3000"),
                () -> assertThat(listOrder).containsExactly(cap.getId(), shoes.getId()),
                () -> assertThat(orderAmounts(pastOrder.id())).containsExactly(2_000L, 1_000L, 2L)
            );
        }

        @DisplayName("관리자 수정 응답은 DB 에서 읽고, 변경 트랜잭션의 결과를 고객 공용 캐시에 채우지 않는다.")
        @Test
        void adminWritesDoNotPublishToCache() {
            BrandModel brand = brandFixture.createBrand("나이키");

            ProductQueryResult created = productFacade.create(brand.getId(), "운동화", 1_000L);
            ProductQueryResult updated = productFacade.update(created.id(), "새 운동화", 2_000L);
            ProductQueryResult adminDetail = productFacade.getProductForAdmin(created.id());

            assertAll(
                () -> assertThat(updated.name()).isEqualTo("새 운동화"),
                () -> assertThat(adminDetail.price()).isEqualTo(2_000L),
                () -> assertThat(hasKey(created.id())).isFalse()
            );
            verify(productDetailCache, never()).put(any(), any());
        }
    }

    @DisplayName("상품·브랜드 삭제")
    @Nested
    class Removal {
        @DisplayName("정상 무효화되면 삭제한 상품의 기본정보 key 가 남지 않는다.")
        @Test
        void removesKeysOfDeletedProducts() {
            BrandModel nike = brandFixture.createBrand("나이키");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 1_000L, 10L);
            ProductModel cap = productFixture.createProduct(nike.getId(), "모자", 2_000L, 10L);
            ProductModel bag = productFixture.createProduct(nike.getId(), "가방", 3_000L, 10L);
            productFacade.getProduct(shoes.getId());
            productFacade.getProduct(cap.getId());
            productFacade.getProduct(bag.getId());

            productFacade.delete(shoes.getId());
            boolean shoesKey = hasKey(shoes.getId());
            brandFacade.delete(nike.getId());

            assertAll(
                () -> assertThat(shoesKey).isFalse(),
                () -> assertThat(hasKey(cap.getId())).isFalse(),
                () -> assertThat(hasKey(bag.getId())).isFalse(),
                () -> assertNotFound(cap.getId())
            );
        }

        @DisplayName("기본정보 key 가 남아 hit 가 되어도 삭제된 상품·브랜드는 노출하지 않고, 다른 브랜드와 과거 주문은 유지한다.")
        @Test
        void hidesDeletedEvenOnHit() {
            BrandModel nike = brandFixture.createBrand("나이키");
            BrandModel adidas = brandFixture.createBrand("아디다스");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 1_000L, 10L);
            ProductModel cap = productFixture.createProduct(nike.getId(), "모자", 2_000L, 10L);
            ProductModel slipper = productFixture.createProduct(adidas.getId(), "슬리퍼", 3_000L, 10L);
            UserModel user = userWithPoint(10_000L);
            OrderInfo pastOrder = confirmedOrder(user, cap.getId(), 1L);
            productFacade.getProduct(shoes.getId());
            productFacade.getProduct(cap.getId());
            productFacade.getProduct(slipper.getId());
            // 무효화가 실패하거나 늦은 채움이 남은 상황을 만들기 위해 삭제만 건너뛴다.
            doNothing().when(productDetailCache).evictAll(anyCollection());

            productFacade.delete(shoes.getId());
            brandFacade.delete(nike.getId());

            assertAll(
                () -> assertThat(hasKey(shoes.getId())).isTrue(),
                () -> assertThat(hasKey(cap.getId())).isTrue(),
                () -> assertNotFound(shoes.getId()),
                () -> assertNotFound(cap.getId()),
                () -> assertThat(productFacade.getProducts(null, FIRST_PAGE, ProductSort.LATEST).items())
                    .extracting(ProductQueryResult::id).containsExactly(slipper.getId()),
                () -> assertThat(productFacade.getProduct(slipper.getId()).brandName()).isEqualTo("아디다스"),
                () -> assertThat(orderAmounts(pastOrder.id())).containsExactly(2_000L, 2_000L, 1L)
            );
        }
    }

    @DisplayName("나머지 영향 쓰기")
    @Nested
    class OtherWrites {
        @DisplayName("브랜드 이름을 바꾸면 그 브랜드 상품들의 기본정보를 삭제하고 새 이름을 반환한다.")
        @Test
        void brandRename() {
            BrandModel nike = brandFixture.createBrand("나이키");
            BrandModel adidas = brandFixture.createBrand("아디다스");
            ProductModel shoes = productFixture.createProduct(nike.getId(), "운동화", 1_000L, 10L);
            ProductModel cap = productFixture.createProduct(nike.getId(), "모자", 2_000L, 10L);
            ProductModel slipper = productFixture.createProduct(adidas.getId(), "슬리퍼", 3_000L, 10L);
            productFacade.getProduct(shoes.getId());
            productFacade.getProduct(cap.getId());
            productFacade.getProduct(slipper.getId());

            brandFacade.update(nike.getId(), "NIKE");

            assertAll(
                () -> assertThat(hasKey(shoes.getId())).isFalse(),
                () -> assertThat(hasKey(cap.getId())).isFalse(),
                () -> assertThat(hasKey(slipper.getId())).isTrue(),
                () -> assertThat(productFacade.getProduct(shoes.getId()).brandName()).isEqualTo("NIKE"),
                () -> assertThat(productFacade.getProduct(cap.getId()).brandName()).isEqualTo("NIKE"),
                () -> assertThat(productFacade.getProduct(slipper.getId()).brandName()).isEqualTo("아디다스")
            );
        }

        @DisplayName("관리자 최종 재고 변경 뒤 새 재고를 반환한다.")
        @Test
        void adminStockChange() {
            ProductModel shoes = productFixture.createProduct("운동화", 1_000L, 10L);
            productFacade.getProduct(shoes.getId());

            productFacade.changeStock(shoes.getId(), 50L);

            assertAll(
                () -> assertThat(hasKey(shoes.getId())).isFalse(),
                () -> assertThat(productFacade.getProduct(shoes.getId()).stockQuantity()).isEqualTo(50L)
            );
        }

        @DisplayName("주문 확정으로 재고가 바뀐 상품들의 기본정보를 삭제하고 줄어든 재고를 반환한다.")
        @Test
        void orderConfirmStockChange() {
            ProductModel shoes = productFixture.createProduct("운동화", 1_000L, 10L);
            ProductModel cap = productFixture.createProduct("모자", 2_000L, 5L);
            ProductModel bag = productFixture.createProduct("가방", 3_000L, 5L);
            productFacade.getProduct(shoes.getId());
            productFacade.getProduct(cap.getId());
            productFacade.getProduct(bag.getId());
            UserModel user = userWithPoint(100_000L);
            OrderInfo order = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shoes.getId(), 3L),
                new OrderItemCommand(cap.getId(), 1L)
            ));

            orderConfirmFacade.confirm(user.getId(), order.id(), PgCommands.forOrder(order.id()));

            assertAll(
                () -> assertThat(hasKey(shoes.getId())).isFalse(),
                () -> assertThat(hasKey(cap.getId())).isFalse(),
                () -> assertThat(hasKey(bag.getId())).isTrue(),
                () -> assertThat(productFacade.getProduct(shoes.getId()).stockQuantity()).isEqualTo(7L),
                () -> assertThat(productFacade.getProduct(cap.getId()).stockQuantity()).isEqualTo(4L),
                () -> assertThat(productFacade.getProduct(bag.getId()).stockQuantity()).isEqualTo(5L)
            );
        }

        @DisplayName("좋아요 등록·취소는 기본정보를 삭제·채우지 않고, hit 상태에서도 DB 좋아요 수를 반영한다. 중복·없는 취소는 기존대로 거절한다.")
        @Test
        void likesUseDatabaseCountOnly() {
            ProductModel shoes = productFixture.createProduct("운동화", 1_000L, 10L);
            UserModel first = userFixture.createUserWithPoint();
            UserModel second = userFixture.createUserWithPoint();
            productFacade.getProduct(shoes.getId());
            long ttlBefore = pttl(shoes.getId());
            clearInvocations(productDetailCache);

            likeFacade.like(first.getId(), shoes.getId());
            likeFacade.like(second.getId(), shoes.getId());
            assertThatThrownBy(() -> likeFacade.like(first.getId(), shoes.getId()))
                .extracting("errorType").isEqualTo(ErrorType.LIKE_ALREADY_EXISTS);
            verify(productDetailCache, never()).evictAll(anyCollection());
            verify(productDetailCache, never()).put(any(), any());
            long afterLikes = productFacade.getProduct(shoes.getId()).likeCount();

            likeFacade.cancel(first.getId(), shoes.getId());
            assertThatThrownBy(() -> likeFacade.cancel(first.getId(), shoes.getId()))
                .extracting("errorType").isEqualTo(ErrorType.LIKE_NOT_FOUND);
            long afterCancel = productFacade.getProduct(shoes.getId()).likeCount();

            assertAll(
                () -> assertThat(afterLikes).isEqualTo(2L),
                () -> assertThat(afterCancel).isEqualTo(1L),
                () -> assertThat(hasKey(shoes.getId())).isTrue(),
                () -> assertThat(pttl(shoes.getId())).isLessThanOrEqualTo(ttlBefore)
            );
            verify(productDetailCache, never()).evictAll(anyCollection());
            verify(productDetailCache, never()).put(eq(shoes.getId()), any());
        }
    }

    @DisplayName("DB rollback")
    @Nested
    class Rollback {
        @DisplayName("변경 SQL 실행·무효화 등록 뒤 실패하면 원본이 유지되고 commit 뒤 삭제는 실행되지 않으며 기존 기본정보가 그대로 맞다.")
        @Test
        void rollbackSkipsEviction() {
            ProductModel shoes = productFixture.createProduct("운동화", 1_000L, 10L);
            productFacade.getProduct(shoes.getId());
            String before = rawValue(shoes.getId());
            RuntimeException injected = new IllegalStateException("변경 뒤 응답 조회 경계에서 주입한 실패");
            // update 는 가격 변경·저장·무효화 등록 뒤 응답을 조회한다. 실제 조회(UPDATE flush 포함)를 수행한 다음 실패시킨다.
            doAnswer(invocation -> {
                invocation.callRealMethod();
                throw injected;
            }).when(productQueryRepository).findDetail(shoes.getId());

            assertThatThrownBy(() -> productFacade.update(shoes.getId(), "운동화", 9_000L)).isSameAs(injected);
            doAnswer(invocation -> invocation.callRealMethod()).when(productQueryRepository).findDetail(any());

            assertAll(
                () -> assertThat(productJpaRepository.findById(shoes.getId()).orElseThrow().getPrice().toWon())
                    .isEqualTo(1_000L),
                () -> assertThat(rawValue(shoes.getId())).isEqualTo(before),
                () -> assertThat(productFacade.getProduct(shoes.getId()).price()).isEqualTo(1_000L)
            );
            verify(productDetailCache, never()).evictAll(anyCollection());
        }
    }
}
