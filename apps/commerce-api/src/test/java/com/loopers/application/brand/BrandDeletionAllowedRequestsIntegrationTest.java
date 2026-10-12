package com.loopers.application.brand;

import com.loopers.application.like.LikeFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.loopers.application.brand.BrandLockTestSupport.TIMEOUT_SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
class BrandDeletionAllowedRequestsIntegrationTest {

    @Autowired private BrandFacade brandFacade;
    @Autowired private ProductFacade productFacade;
    @Autowired private OrderFacade orderFacade;
    @Autowired private LikeFacade likeFacade;
    @MockitoSpyBean private BrandRepository brandRepository;
    @MockitoSpyBean private ProductRepository productRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private LikeRepository likeRepository;
    @Autowired private PointRepository pointRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private BrandLockTestSupport locks;

    @BeforeEach
    void setUp() {
        locks = new BrandLockTestSupport(dataSource, jdbcTemplate, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @ParameterizedTest(name = "Brand 배타 잠금 뒤에도 Product 변경은 허용한다: {0}")
    @EnumSource(ProductChange.class)
    void productChangeSucceedsBeforeBulkDeletionWithoutBrandSharedLock(ProductChange change) throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch deletionLocked = new CountDownLatch(1);
        CountDownLatch allowDeletion = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);

        doAnswer(invocation -> {
            deletionLocked.countDown();
            locks.awaitLatch(allowDeletion);
            return invocation.callRealMethod();
        }).when(productRepository).softDeleteActiveByBrandId(eq(fixture.brandId()), any());

        try {
            Future<?> deletion = locks.submit(workers, () -> brandFacade.delete(fixture.brandId()));
            assertThat(deletionLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<?> mutation = locks.submit(workers, () -> changeProduct(change, fixture.productId()));
            mutation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Product beforeDeletion = readProduct(fixture.productId());

            allowDeletion.countDown();
            deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            Product afterDeletion = readProduct(fixture.productId());
            assertThat(afterDeletion.getDeletedAt()).isNotNull();
            assertThat(afterDeletion.getName()).isEqualTo(change == ProductChange.DETAILS ? "Changed" : "First");
            assertThat(afterDeletion.getPrice()).isEqualTo(change == ProductChange.DETAILS ? 200L : 100L);
            assertThat(afterDeletion.getStock().amount()).isEqualTo(change == ProductChange.STOCK ? 9L : 5L);
            if (change == ProductChange.DELETE) {
                assertThat(afterDeletion.getDeletedAt()).isEqualTo(beforeDeletion.getDeletedAt());
            } else {
                assertThat(beforeDeletion.getDeletedAt()).isNull();
            }
            verify(brandRepository, never()).findActiveByIdWithSharedLock(anyLong());
        } finally {
            allowDeletion.countDown();
            locks.shutDown(workers);
        }
    }

    @ParameterizedTest(name = "상품 삭제 UPDATE가 먼저면 Product 변경은 대기 후 거절한다: {0}")
    @EnumSource(ProductChange.class)
    void productChangeWaitsForProductDeletionAndRejectsAfterCommit(ProductChange change) throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch deletionWritesCompleted = new CountDownLatch(1);
        CountDownLatch allowDeletionCommit = new CountDownLatch(1);
        CountDownLatch mutationStarted = new CountDownLatch(1);
        AtomicLong mutationConnection = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try {
            Future<?> deletion = locks.submit(workers, () -> {
                brandFacade.delete(fixture.brandId());
                deletionWritesCompleted.countDown();
                locks.awaitLatch(allowDeletionCommit);
            });
            assertThat(deletionWritesCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<?> mutation = locks.submit(workers, mutationConnection, mutationStarted,
                () -> changeProduct(change, fixture.productId()));
            assertThat(mutationStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            locks.awaitLockWait(mutationConnection.get(), "products");

            allowDeletionCommit.countDown();
            deletion.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThatThrownBy(() -> mutation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).cause()
                .isInstanceOfSatisfying(CoreException.class, exception ->
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND));
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Product product = productRepository.findById(fixture.productId()).orElseThrow();
                assertThat(product.getDeletedAt()).isEqualTo(
                    brandRepository.findById(fixture.brandId()).orElseThrow().getDeletedAt());
                assertThat(product.getName()).isEqualTo("First");
                assertThat(product.getPrice()).isEqualTo(100L);
                assertThat(product.getStock().amount()).isEqualTo(5L);
            });
            verify(brandRepository, never()).findActiveByIdWithSharedLock(anyLong());
        } finally {
            allowDeletionCommit.countDown();
            locks.shutDown(workers);
        }
    }

    @ParameterizedTest(name = "미삭제 상품 조회 후 삭제가 commit되어도 관계 저장은 허용한다: {0}")
    @EnumSource(NewRelation.class)
    void relationCanBeSavedAfterDeletionIfActiveProductWasAlreadyRead(NewRelation relation) throws Exception {
        Fixture fixture = createFixture();
        CountDownLatch activeProductRead = new CountDownLatch(1);
        CountDownLatch allowSave = new CountDownLatch(1);
        AtomicBoolean firstRead = new AtomicBoolean(true);
        ExecutorService workers = Executors.newSingleThreadExecutor();

        // 장벽은 테스트의 조회 경계에만 둔다. 실제 Facade에는 대기나 실패용 분기를 추가하지 않는다.
        doAnswer(invocation -> {
            Object product = invocation.callRealMethod();
            if (firstRead.getAndSet(false)) {
                activeProductRead.countDown();
                locks.awaitLatch(allowSave);
            }
            return product;
        }).when(productRepository).findById(fixture.productId());

        try {
            Future<?> creation = locks.submit(workers, () -> createRelation(relation, fixture));
            assertThat(activeProductRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            brandFacade.delete(fixture.brandId());
            allowSave.countDown();
            creation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThatThrownBy(() -> createRelation(relation, fixture))
                .isInstanceOfSatisfying(CoreException.class, exception ->
                    assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND));
            if (relation == NewRelation.DRAFT) {
                Order order = new TransactionTemplate(transactionManager).execute(status -> {
                    List<Order> orders = orderRepository.findAllByUserId(fixture.userId());
                    assertThat(orders).hasSize(1);
                    return orders.getFirst();
                });
                assertThatThrownBy(() -> orderFacade.confirm(fixture.userId(), order.getId()))
                    .isInstanceOfSatisfying(CoreException.class, exception ->
                        assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    Order savedOrder = orderRepository.findById(order.getId()).orElseThrow();
                    assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.DRAFT);
                    assertThat(savedOrder.getTotalAmount()).isEqualTo(100L);
                    assertThat(savedOrder.getPaymentAmount()).isNull();
                    assertThat(savedOrder.getPaymentResult()).isNull();
                });
            } else {
                assertThat(likeRepository.findByUserIdAndProductId(fixture.userId(), fixture.productId()))
                    .isPresent();
                assertThat(likeFacade.getMyLikes(fixture.userId())).isEmpty();
                assertThat(likeFacade.cancel(fixture.userId(), fixture.productId()).liked()).isFalse();
                assertThat(likeRepository.findByUserIdAndProductId(fixture.userId(), fixture.productId()))
                    .isEmpty();
            }
            assertThat(readProduct(fixture.productId()).getStock().amount()).isEqualTo(5L);
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(pointRepository.findByUserId(fixture.userId()).orElseThrow().getBalance().amount())
                    .isEqualTo(1_000L));
            verify(brandRepository, never()).findActiveByIdWithSharedLock(anyLong());
        } finally {
            allowSave.countDown();
            locks.shutDown(workers);
        }
    }

    private void changeProduct(ProductChange change, Long productId) {
        switch (change) {
            case DETAILS -> productFacade.update(productId, "Changed", 200L);
            case STOCK -> productFacade.changeStock(productId, 9L);
            case DELETE -> productFacade.delete(productId);
        }
    }

    private void createRelation(NewRelation relation, Fixture fixture) {
        switch (relation) {
            case DRAFT -> orderFacade.create(fixture.userId(), List.of(
                new OrderFacade.OrderRequestItem(fixture.productId(), 1)));
            case LIKE -> likeFacade.add(fixture.userId(), fixture.productId());
        }
    }

    private Product readProduct(Long productId) {
        return new TransactionTemplate(transactionManager).execute(status ->
            productRepository.findById(productId).orElseThrow());
    }

    private Fixture createFixture() {
        User user = userRepository.save(User.create());
        pointRepository.save(Point.create(user.getId(), new PointBalance(1_000L)));
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = Product.create(brand.getId(), "First", 100L);
        product.changeStockTo(5L);
        product = productRepository.save(product);
        return new Fixture(brand.getId(), product.getId(), user.getId());
    }

    private enum ProductChange { DETAILS, STOCK, DELETE }
    private enum NewRelation { DRAFT, LIKE }
    private record Fixture(Long brandId, Long productId, Long userId) {}
}
