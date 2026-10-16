package com.loopers.infrastructure.query.mall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.command.ProductCommand;
import com.loopers.application.mall.query.ProductDetailView;
import com.loopers.application.mall.query.ProductQueryDao;
import com.loopers.application.mall.usecase.DeleteBrandUseCase;
import com.loopers.application.mall.usecase.DeleteProductUseCase;
import com.loopers.application.mall.usecase.SetProductStockUseCase;
import com.loopers.application.mall.usecase.UpdateBrandUseCase;
import com.loopers.application.mall.usecase.UpdateProductUseCase;
import com.loopers.application.ordering.command.ConfirmOrderCommand;
import com.loopers.application.ordering.usecase.ConfirmOrderUseCase;
import com.loopers.application.shopping.command.LikeCommand;
import com.loopers.application.shopping.service.LikeCountDeltaBuffer;
import com.loopers.application.shopping.usecase.CancelLikeUseCase;
import com.loopers.application.shopping.usecase.FlushLikeCountDeltaUseCase;
import com.loopers.application.shopping.usecase.LikeCountAggregationUseCase;
import com.loopers.application.shopping.usecase.RegisterLikeUseCase;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.pay.repository.WalletRepository;
import com.loopers.domain.shopping.model.Like;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.LikeRepository;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import com.loopers.infrastructure.persistence.mall.entity.QProductJpaEntity;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
@TestPropertySource(properties = {
    "query.product-detail-cache.enabled=true",
    "query.product-detail-cache.ttl=1h",
})
// 테스트 자체를 트랜잭션으로 감싸지 않고 실제 커밋 후 리스너와 캐시를 검증한다.
class ProductDetailCacheInvalidationIntegrationTest {
    private static final QProductJpaEntity PRODUCT = QProductJpaEntity.productJpaEntity;

    @Autowired
    private ProductQueryDao productQueryDao;
    @Autowired
    private ProductDetailCache cache;
    @Autowired
    private UpdateProductUseCase updateProduct;
    @Autowired
    private SetProductStockUseCase setStock;
    @Autowired
    private DeleteProductUseCase deleteProduct;
    @Autowired
    private UpdateBrandUseCase updateBrand;
    @Autowired
    private DeleteBrandUseCase deleteBrand;
    @Autowired
    private RegisterLikeUseCase registerLike;
    @Autowired
    private CancelLikeUseCase cancelLike;
    @Autowired
    private FlushLikeCountDeltaUseCase flushDeltas;
    @Autowired
    private LikeCountAggregationUseCase recount;
    @Autowired
    private LikeCountDeltaBuffer deltaBuffer;
    @Autowired
    private ConfirmOrderUseCase confirmOrder;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LikeRepository likeRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @BeforeEach
    void setUp() {
        cache.invalidateAll();
        deltaBuffer.drain();
    }

    @AfterEach
    void tearDown() {
        cache.invalidateAll();
        deltaBuffer.drain();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품 변경과 커밋 경계")
    @Nested
    class ProductChanges {
        @DisplayName("상품 수정 커밋 뒤 최신 상세를 읽고 다른 상품 캐시는 보존한다")
        @Test
        void reloadsUpdatedProductAfterCommit() {
            // arrange
            Brand brand = createBrand();
            long productId = createProduct(brand);
            long otherId = createProduct(brand);
            ProductDetailView before = detail(productId);
            ProductDetailView other = detail(otherId);

            // act
            updateProduct.execute(new ProductCommand.Update(productId, "수정 상품", "수정 설명", 2_000L));

            // assert
            ProductDetailView after = detail(productId);
            assertThat(after).isNotSameAs(before);
            assertThat(after.name()).isEqualTo("수정 상품");
            assertThat(after.description()).isEqualTo("수정 설명");
            assertThat(after.price()).isEqualTo(2_000L);
            assertThat(detail(otherId)).isSameAs(other);
        }

        @DisplayName("재고 설정 커밋 뒤 캐시에서 이전 재고를 반환하지 않는다")
        @Test
        void reloadsStockAfterCommit() {
            // arrange
            long productId = createProduct(createBrand());
            ProductDetailView before = detail(productId);

            // act
            setStock.execute(new ProductCommand.SetStock(productId, 20));

            // assert
            assertThat(detail(productId)).isNotSameAs(before).extracting(ProductDetailView::stock).isEqualTo(20);
        }

        @DisplayName("상품 삭제 커밋 뒤 상세가 없고 다른 상품 캐시는 보존한다")
        @Test
        void removesDeletedProductAfterCommit() {
            // arrange
            Brand brand = createBrand();
            long productId = createProduct(brand);
            long otherId = createProduct(brand);
            detail(productId);
            ProductDetailView other = detail(otherId);

            // act
            deleteProduct.execute(new ProductCommand.Delete(productId));

            // assert
            assertThat(productQueryDao.findProduct(productId)).isEmpty();
            assertThat(detail(otherId)).isSameAs(other);
        }

        @DisplayName("상품 수정·재고 설정·삭제를 롤백하면 DB와 기존 캐시를 보존한다")
        @Test
        void keepsCachedDetailAfterRollback() {
            // arrange
            long productId = createProduct(createBrand());
            ProductDetailView before = detail(productId);

            // act
            transactionTemplate.executeWithoutResult(status -> {
                updateProduct.execute(new ProductCommand.Update(productId, "롤백 상품", "롤백 설명", 2_000L));
                setStock.execute(new ProductCommand.SetStock(productId, 20));
                assertThat(detail(productId).stock()).isEqualTo(20);
                assertThat(detail(productId).name()).isEqualTo("롤백 상품");
                deleteProduct.execute(new ProductCommand.Delete(productId));
                assertThat(productQueryDao.findProduct(productId)).isEmpty();
                status.setRollbackOnly();
            });

            // assert
            assertThat(detail(productId)).isSameAs(before);
            Product stored = productRepository.findById(productId).orElseThrow();
            assertThat(stored.getName()).isEqualTo("상품");
            assertThat(stored.getStock()).isEqualTo(5);
            assertThat(stored.isDeleted()).isFalse();
        }
    }

    @DisplayName("브랜드 범위 무효화")
    @Nested
    class BrandChanges {
        @DisplayName("브랜드 수정 커밋 뒤 소속 상품을 갱신하고 다른 브랜드 캐시는 보존한다")
        @Test
        void reloadsOnlyProductsOfUpdatedBrand() {
            // arrange
            Brand brand = createBrand();
            long first = createProduct(brand);
            long second = createProduct(brand);
            long otherId = createProduct(createBrand());
            ProductDetailView firstBefore = detail(first);
            ProductDetailView secondBefore = detail(second);
            ProductDetailView other = detail(otherId);

            // act
            updateBrand.execute(new BrandCommand.Update(brand.getId(), "수정 브랜드", "수정 설명"));

            // assert
            assertThat(detail(first)).isNotSameAs(firstBefore);
            assertThat(detail(second)).isNotSameAs(secondBefore);
            assertThat(detail(first).brand().name()).isEqualTo("수정 브랜드");
            assertThat(detail(second).brand().name()).isEqualTo("수정 브랜드");
            assertThat(detail(otherId)).isSameAs(other);
        }

        @DisplayName("브랜드 일괄 삭제 커밋 뒤 소속 상세만 사라지고 다른 브랜드 캐시는 보존한다")
        @Test
        void removesOnlyProductsOfDeletedBrand() {
            // arrange
            Brand brand = createBrand();
            long first = createProduct(brand);
            long second = createProduct(brand);
            long otherId = createProduct(createBrand());
            detail(first);
            detail(second);
            ProductDetailView other = detail(otherId);

            // act
            deleteBrand.execute(new BrandCommand.Delete(brand.getId()));

            // assert
            assertThat(productQueryDao.findProduct(first)).isEmpty();
            assertThat(productQueryDao.findProduct(second)).isEmpty();
            assertThat(detail(otherId)).isSameAs(other);
        }

        @DisplayName("브랜드 수정·삭제 롤백 뒤 소속 상품의 기존 캐시와 DB가 보존된다")
        @Test
        void keepsBrandProductDetailsAfterRollback() {
            // arrange
            Brand brand = createBrand();
            long first = createProduct(brand);
            long second = createProduct(brand);
            ProductDetailView firstBefore = detail(first);
            ProductDetailView secondBefore = detail(second);

            // act
            transactionTemplate.executeWithoutResult(status -> {
                updateBrand.execute(new BrandCommand.Update(brand.getId(), "롤백 브랜드", null));
                deleteBrand.execute(new BrandCommand.Delete(brand.getId()));
                status.setRollbackOnly();
            });

            // assert
            assertThat(detail(first)).isSameAs(firstBefore);
            assertThat(detail(second)).isSameAs(secondBefore);
            Brand stored = brandRepository.findById(brand.getId()).orElseThrow();
            assertThat(stored.getName()).isEqualTo("브랜드");
            assertThat(stored.isDeleted()).isFalse();
            assertThat(productRepository.findById(first).orElseThrow().isDeleted()).isFalse();
        }
    }

    @DisplayName("좋아요 관계와 집계 경계")
    @Nested
    class LikeChanges {
        @DisplayName("관계 커밋 후 집계 전 재조회한 상세도 증감 집계 커밋 후 다시 갱신된다")
        @Test
        void invalidatesBothRelationshipAndDeltaCommit() {
            // arrange
            long productId = createProduct(createBrand());
            long otherId = createProduct(createBrand());
            userRepository.save(User.create(1L));
            ProductDetailView before = detail(productId);
            ProductDetailView other = detail(otherId);

            // act & assert: 관계 커밋은 상세를 무효화하지만 저장 집계는 아직 0이다.
            registerLike.execute(new LikeCommand.Register(1L, productId));
            ProductDetailView beforeFlush = detail(productId);
            assertThat(beforeFlush).isNotSameAs(before);
            assertThat(beforeFlush.likeCount()).isZero();

            flushDeltas.execute();
            ProductDetailView afterFlush = detail(productId);
            assertThat(afterFlush).isNotSameAs(beforeFlush);
            assertThat(afterFlush.likeCount()).isEqualTo(1L);

            cancelLike.execute(new LikeCommand.Cancel(1L, productId));
            ProductDetailView beforeCancelFlush = detail(productId);
            assertThat(beforeCancelFlush).isNotSameAs(afterFlush);
            assertThat(beforeCancelFlush.likeCount()).isEqualTo(1L);
            flushDeltas.execute();
            assertThat(detail(productId)).isNotSameAs(beforeCancelFlush)
                .extracting(ProductDetailView::likeCount).isEqualTo(0L);
            assertThat(detail(otherId)).isSameAs(other);
        }

        @DisplayName("외부 TX에서 증감 집계 SQL 실행 뒤 롤백하면 DB와 대상·무관 상세 캐시를 보존한다")
        @Test
        void keepsCachedDetailsAndStoredCountAfterDeltaFlushRollback() {
            // arrange: 관계는 먼저 커밋하고, 아직 0인 저장 집계의 상세를 캐시에 채운다.
            long productId = createProduct(createBrand());
            long otherId = createProduct(createBrand());
            userRepository.save(User.create(1L));
            registerLike.execute(new LikeCommand.Register(1L, productId));
            ProductDetailView before = detail(productId);
            ProductDetailView other = detail(otherId);
            assertThat(before.likeCount()).isZero();

            // act: JDBC 집계 쓰기와 검증용 SELECT만 수행하며 JPA 쓰기는 섞지 않는다.
            transactionTemplate.executeWithoutResult(status -> {
                flushDeltas.execute();
                assertThat(queryFactory.select(PRODUCT.likeCount).from(PRODUCT)
                    .where(PRODUCT.id.eq(productId)).fetchOne()).isEqualTo(1L);
                status.setRollbackOnly();
            });

            // assert: 외부 TX 사후 rollback의 delta 버퍼 복원 여부는 기존 범위 밖이다.
            assertThat(queryFactory.select(PRODUCT.likeCount).from(PRODUCT)
                .where(PRODUCT.id.eq(productId)).fetchOne()).isEqualTo(0L);
            assertThat(detail(productId)).isSameAs(before);
            assertThat(detail(productId).likeCount()).isZero();
            assertThat(detail(otherId)).isSameAs(other);
        }

        @DisplayName("좋아요 관계 롤백은 상세 캐시와 증감 버퍼를 바꾸지 않는다")
        @Test
        void keepsCachedDetailWhenRelationshipRollsBack() {
            // arrange
            long productId = createProduct(createBrand());
            userRepository.save(User.create(1L));
            ProductDetailView before = detail(productId);

            // act
            transactionTemplate.executeWithoutResult(status -> {
                registerLike.execute(new LikeCommand.Register(1L, productId));
                status.setRollbackOnly();
            });

            // assert
            assertThat(detail(productId)).isSameAs(before);
            assertThat(deltaBuffer.drain()).isEmpty();
            recount.execute();
            assertThat(detail(productId).likeCount()).isZero();
        }

        @DisplayName("전체 재집계 커밋은 모든 상세를 갱신하고 사라진 관계의 집계도 0으로 읽는다")
        @Test
        void invalidatesAllAfterRecountCommit() {
            // arrange: 관계와 저장 집계가 다른 상태를 JPA/QueryDSL fixture로 만든다.
            long liked = createProduct(createBrand());
            long stale = createProduct(createBrand());
            transactionTemplate.executeWithoutResult(status -> {
                likeRepository.save(Like.create(1L, liked));
                likeRepository.save(Like.create(2L, liked));
                queryFactory.update(PRODUCT).set(PRODUCT.likeCount, 7L).where(PRODUCT.id.eq(stale)).execute();
            });
            ProductDetailView likedBefore = detail(liked);
            ProductDetailView staleBefore = detail(stale);
            assertThat(staleBefore.likeCount()).isEqualTo(7L);

            // act
            recount.execute();

            // assert
            assertThat(detail(liked)).isNotSameAs(likedBefore).extracting(ProductDetailView::likeCount).isEqualTo(2L);
            assertThat(detail(stale)).isNotSameAs(staleBefore).extracting(ProductDetailView::likeCount).isEqualTo(0L);
        }

        @DisplayName("전체 재집계 롤백은 기존 상세 캐시와 저장 집계를 보존한다")
        @Test
        void keepsCachedDetailAfterRecountRollback() {
            // arrange
            long productId = createProduct(createBrand());
            transactionTemplate.executeWithoutResult(status -> queryFactory.update(PRODUCT)
                .set(PRODUCT.likeCount, 7L).where(PRODUCT.id.eq(productId)).execute());
            ProductDetailView before = detail(productId);

            // act: 이 트랜잭션에는 JDBC 집계 쓰기만 있고 JPA 쓰기를 섞지 않는다.
            transactionTemplate.executeWithoutResult(status -> {
                recount.execute();
                status.setRollbackOnly();
            });

            // assert
            assertThat(detail(productId)).isSameAs(before);
            assertThat(queryFactory.select(PRODUCT.likeCount).from(PRODUCT)
                .where(PRODUCT.id.eq(productId)).fetchOne()).isEqualTo(7L);
        }
    }

    @DisplayName("주문 확정의 바깥 트랜잭션")
    @Nested
    class OrderChanges {
        @DisplayName("주문 확정 커밋 뒤 모든 품목의 최신 재고를 읽고 무관한 캐시는 보존한다")
        @Test
        void reloadsOrderedProductsAfterFacadeCommit() {
            // arrange
            Brand brand = createBrand();
            long first = createProduct(brand);
            long second = createProduct(brand);
            long otherId = createProduct(brand);
            createWallet(10_000L);
            Order order = orderRepository.save(Order.create(1L, List.of(
                OrderItem.create(first, "상품", 1_000L, 2), OrderItem.create(second, "상품", 1_000L, 1))));
            ProductDetailView firstBefore = detail(first);
            ProductDetailView secondBefore = detail(second);
            ProductDetailView other = detail(otherId);

            // act
            confirmOrder.execute(new ConfirmOrderCommand(order.getId()));

            // assert
            assertThat(detail(first)).isNotSameAs(firstBefore).extracting(ProductDetailView::stock).isEqualTo(3);
            assertThat(detail(second)).isNotSameAs(secondBefore).extracting(ProductDetailView::stock).isEqualTo(4);
            assertThat(detail(otherId)).isSameAs(other);
            assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(7_000L);
        }

        @DisplayName("재고 변경 이벤트 발행 뒤 결제가 실패해도 롤백된 재고와 기존 캐시를 보존한다")
        @Test
        void keepsCacheWhenPaymentFailsAfterStockDecrease() {
            // arrange
            long productId = createProduct(createBrand());
            createWallet(0L);
            Order order = orderRepository.save(Order.create(1L,
                List.of(OrderItem.create(productId, "상품", 1_000L, 2))));
            ProductDetailView before = detail(productId);

            // act & assert
            assertThatThrownBy(() -> confirmOrder.execute(new ConfirmOrderCommand(order.getId())))
                .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo(DomainErrorCode.INSUFFICIENT_POINT);
            assertThat(detail(productId)).isSameAs(before);
            assertThat(detail(productId).stock()).isEqualTo(5);
            assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(5);
            assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isZero();
        }
    }

    private Brand createBrand() {
        return brandRepository.save(Brand.create("브랜드", null));
    }

    private long createProduct(Brand brand) {
        return productRepository.save(Product.create(brand.getId(), "상품", "설명", 1_000L, 5)).getId();
    }

    private ProductDetailView detail(long productId) {
        return productQueryDao.findProduct(productId).orElseThrow();
    }

    private void createWallet(long balance) {
        userRepository.save(User.create(1L));
        walletRepository.save(Wallet.restore(1L, balance));
    }
}
