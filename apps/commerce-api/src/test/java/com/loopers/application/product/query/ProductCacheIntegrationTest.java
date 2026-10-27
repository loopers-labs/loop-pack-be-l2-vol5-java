package com.loopers.application.product.query;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderItemCommand;
import com.loopers.application.order.query.OrderReader;
import com.loopers.application.order.query.OrderView;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.productlike.ProductLikeFacade;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.user.UserModel;
import com.loopers.support.error.ErrorType;
import com.loopers.support.fixture.Fixtures;
import com.loopers.support.paging.PageQuery;
import com.loopers.support.paging.PageResult;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.loopers.support.ErrorAssertions.assertThrowsErrorType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DR-37 고객 상품 조회 캐시의 시나리오. 상세와 latest 첫 페이지(size 20)가 대상이다.
 * fixture 는 첫 조회 전에 모두 만든다. fixture 는 revision 을 올리지 않으므로, 조회 뒤의 변경은 Facade 로만 한다.
 * hit 여부는 결과 조회(findActive·findActivePage) 호출이 없는지로 본다. revision·좋아요 수 조회는 hit 에도 나간다.
 */
@SpringBootTest
class ProductCacheIntegrationTest {
    private static final PageQuery LATEST_FIRST_PAGE = PageQuery.of(0, 20);
    private static final long WAIT_SECONDS = 10;

    @Autowired
    private ProductReader productReader;
    @Autowired
    private ProductFacade productFacade;
    @Autowired
    private BrandFacade brandFacade;
    @Autowired
    private ProductLikeFacade productLikeFacade;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private OrderReader orderReader;
    @MockitoSpyBean
    private ProductQueryRepository productQueryRepository;
    @MockitoSpyBean
    private ProductCache productCache;
    @Autowired
    private RedisTemplate<String, String> redisTemplate;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private Fixtures fixtures;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private RedisCleanUp redisCleanUp;

    private UserModel user;
    private UserModel admin;
    private BrandModel brand;

    @BeforeEach
    void setUp() {
        fixtures.catalogRevision();
        user = fixtures.userWithBalance(1_000_000L);
        admin = fixtures.admin();
        brand = fixtures.brand("브랜드");
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private ProductView.Summary detail(Long productId) {
        return productReader.getProduct(user.getId(), productId);
    }

    private PageResult<ProductView.Summary> latestPage() {
        return productReader.listProducts(user.getId(), null, ProductSort.LATEST, LATEST_FIRST_PAGE);
    }

    private static List<Long> ids(PageResult<ProductView.Summary> page) {
        return page.items().stream().map(ProductView.Summary::id).toList();
    }

    private static ProductView.Summary itemOf(PageResult<ProductView.Summary> page, Long productId) {
        return page.items().stream().filter(item -> item.id().equals(productId)).findFirst().orElseThrow();
    }

    private OrderInfo confirmedOrder(Long productId, int quantity) {
        OrderInfo order = orderFacade.createOrder(user.getId(), List.of(new OrderItemCommand(productId, quantity)));
        return orderFacade.confirmOrder(user.getId(), order.id(), "attempt-" + order.id(), "SAMSUNG", "1234-5678-9814-1451");
    }

    /** 결과 조회 없이 응답했는지 (hit). */
    private void assertServedFromCache() {
        verify(productQueryRepository, never()).findActive(any());
        verify(productQueryRepository, never()).findActivePage(any(), any(), any());
    }

    private static String detailKey(Long productId, long productRevision, long brandRevision) {
        return "product:detail:v1:" + productId + ":" + productRevision + ":" + brandRevision;
    }

    private static String latestPageKey(long listRevision) {
        return "product:list:latest:v1:s20:" + listRevision;
    }

    @Nested
    @DisplayName("동일 입력의 DB-only·miss·hit")
    class SameInput {
        @DisplayName("[DR-37] 상세와 latest 첫 페이지는 DB 직접 조회·miss·hit 결과가 같고, hit 은 결과 조회(목록·count·상세)를 하지 않는다.")
        @Test
        void dbOnlyMissAndHit_returnSameResult() {
            BrandModel other = fixtures.brand("다른 브랜드");
            List<ProductModel> products = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                products.add(fixtures.product(i % 2 == 0 ? brand.getId() : other.getId(), "상품" + i, 1000L + i, 5));
            }
            fixtures.deletedProduct(brand.getId(), "삭제됨", 500L, 1);
            ProductModel target = products.get(24);
            fixtures.like(user.getId(), target.getId());
            fixtures.like(admin.getId(), target.getId());

            PageResult<ProductView.Summary> dbOnlyPage = productQueryRepository.findActivePage(null, ProductSort.LATEST, LATEST_FIRST_PAGE);
            ProductView.Summary dbOnlyDetail = productQueryRepository.findActive(target.getId()).orElseThrow();
            clearInvocations(productQueryRepository);

            PageResult<ProductView.Summary> missPage = latestPage();
            ProductView.Summary missDetail = detail(target.getId());
            verify(productQueryRepository).findActivePage(null, ProductSort.LATEST, LATEST_FIRST_PAGE);
            verify(productQueryRepository).findActive(target.getId());
            clearInvocations(productQueryRepository);

            PageResult<ProductView.Summary> hitPage = latestPage();
            ProductView.Summary hitDetail = detail(target.getId());
            assertServedFromCache();

            assertThat(dbOnlyPage.items()).hasSize(20);
            assertThat(dbOnlyPage.totalCount()).isEqualTo(25);
            assertThat(dbOnlyDetail.likeCount()).isEqualTo(2);
            assertThat(missPage).isEqualTo(dbOnlyPage);
            assertThat(hitPage).isEqualTo(dbOnlyPage);
            assertThat(missDetail).isEqualTo(dbOnlyDetail);
            assertThat(hitDetail).isEqualTo(dbOnlyDetail);
        }
    }

    @Nested
    @DisplayName("관리자 가격 수정 commit 후 재조회")
    class PriceUpdate {
        @DisplayName("[DR-37][ASM-10] 수정 뒤 상세·목록에 새 가격이 나오고 latest 순서는 그대로다. 수정 전 확정한 주문 금액은 그대로다.")
        @Test
        void showsNewPrice_keepsOrderAmount() {
            ProductModel target = fixtures.product(brand.getId(), "상품", 1000L, 10);
            ProductModel other = fixtures.product(brand.getId(), "다른 상품", 3000L, 10);
            OrderInfo order = confirmedOrder(target.getId(), 2);
            List<Long> orderBefore = ids(latestPage());
            detail(target.getId());

            productFacade.updateProduct(admin.getId(), target.getId(), "상품", 5000L);

            assertThat(detail(target.getId()).price()).isEqualTo(5000L);
            PageResult<ProductView.Summary> page = latestPage();
            assertThat(itemOf(page, target.getId()).price()).isEqualTo(5000L);
            assertThat(itemOf(page, other.getId()).price()).isEqualTo(3000L);
            assertThat(ids(page)).isEqualTo(orderBefore);
            OrderView.MyDetail placed = orderReader.getMyOrder(user.getId(), order.id());
            assertThat(placed.totalAmount()).isEqualTo(2000L);
            assertThat(placed.items().get(0).unitPrice()).isEqualTo(1000L);
        }
    }

    @Nested
    @DisplayName("캐시에 담긴 상품·브랜드 삭제")
    class Deletion {
        @DisplayName("[DR-37][INV-10] 브랜드를 삭제하면 그 상품은 상세·목록·totalCount 에서 빠지고, 다른 브랜드 상품과 확정된 주문은 그대로다.")
        @Test
        void brandDeletion_removesItsProductsOnly() {
            BrandModel other = fixtures.brand("다른 브랜드");
            ProductModel a1 = fixtures.product(brand.getId(), "A1", 1000L, 10);
            ProductModel a2 = fixtures.product(brand.getId(), "A2", 1000L, 10);
            ProductModel b1 = fixtures.product(other.getId(), "B1", 1000L, 10);
            OrderInfo order = confirmedOrder(a1.getId(), 1);
            detail(a1.getId());
            detail(b1.getId());
            assertThat(latestPage().totalCount()).isEqualTo(3);

            brandFacade.deleteBrand(admin.getId(), brand.getId());

            assertThrowsErrorType(() -> detail(a1.getId()), ErrorType.PRODUCT_NOT_FOUND);
            assertThrowsErrorType(() -> detail(a2.getId()), ErrorType.PRODUCT_NOT_FOUND);
            assertThat(detail(b1.getId()).name()).isEqualTo("B1");
            PageResult<ProductView.Summary> page = latestPage();
            assertThat(ids(page)).containsExactly(b1.getId());
            assertThat(page.totalCount()).isEqualTo(1);
            OrderView.MyDetail placed = orderReader.getMyOrder(user.getId(), order.id());
            assertThat(placed.items()).extracting(OrderView.Item::productId).containsExactly(a1.getId());
            assertThat(placed.totalAmount()).isEqualTo(1000L);
        }

        @DisplayName("[DR-37][ST-02] 상품을 삭제하면 그 상품만 상세·목록·totalCount 에서 빠진다.")
        @Test
        void productDeletion_removesThatProduct() {
            ProductModel target = fixtures.product(brand.getId(), "상품", 1000L, 10);
            ProductModel other = fixtures.product(brand.getId(), "다른 상품", 1000L, 10);
            detail(target.getId());
            latestPage();

            productFacade.deleteProduct(admin.getId(), target.getId());

            assertThrowsErrorType(() -> detail(target.getId()), ErrorType.PRODUCT_NOT_FOUND);
            PageResult<ProductView.Summary> page = latestPage();
            assertThat(ids(page)).containsExactly(other.getId());
            assertThat(page.totalCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("캐시 내용에 영향을 주는 나머지 쓰기")
    class OtherWrites {
        @DisplayName("[DR-37] 상품 생성은 latest 첫 페이지 맨 앞에 나오고 totalCount 가 늘어난다.")
        @Test
        void creation_appearsFirst() {
            fixtures.product(brand.getId(), "상품", 1000L, 10);
            latestPage();

            Long createdId = productFacade.createProduct(admin.getId(), brand.getId(), "새 상품", 1000L, 10).id();

            PageResult<ProductView.Summary> page = latestPage();
            assertThat(page.items().get(0).id()).isEqualTo(createdId);
            assertThat(page.totalCount()).isEqualTo(2);
        }

        @DisplayName("[DR-37] 상품 이름 수정과 브랜드 이름 수정은 상세·목록에 반영된다.")
        @Test
        void nameUpdates_areShown() {
            ProductModel target = fixtures.product(brand.getId(), "상품", 1000L, 10);
            detail(target.getId());
            latestPage();

            productFacade.updateProduct(admin.getId(), target.getId(), "새 상품 이름", 1000L);
            brandFacade.updateBrand(admin.getId(), brand.getId(), "새 브랜드 이름");

            ProductView.Summary shown = detail(target.getId());
            assertThat(shown.name()).isEqualTo("새 상품 이름");
            assertThat(shown.brandName()).isEqualTo("새 브랜드 이름");
            ProductView.Summary listed = itemOf(latestPage(), target.getId());
            assertThat(listed.name()).isEqualTo("새 상품 이름");
            assertThat(listed.brandName()).isEqualTo("새 브랜드 이름");
        }

        @DisplayName("[DR-37][DR-22] 재고 변경·주문 확정은 고객 조회 결과가 아니라 캐시를 그대로 쓴다.")
        @Test
        void stockChanges_keepCache() {
            ProductModel target = fixtures.product(brand.getId(), "상품", 1000L, 10);
            ProductView.Summary before = detail(target.getId());
            PageResult<ProductView.Summary> pageBefore = latestPage();

            productFacade.updateStock(admin.getId(), target.getId(), 50);
            confirmedOrder(target.getId(), 3);
            clearInvocations(productQueryRepository);

            assertThat(detail(target.getId())).isEqualTo(before);
            assertThat(latestPage()).isEqualTo(pageBefore);
            assertServedFromCache();
        }

        @DisplayName("[DR-37][INV-05] 좋아요 수는 캐시에 담지 않아, hit 에서도 등록·취소가 바로 보인다.")
        @Test
        void likeCount_isFreshOnHit() {
            ProductModel target = fixtures.product(brand.getId(), "상품", 1000L, 10);
            detail(target.getId());
            latestPage();

            productLikeFacade.like(user.getId(), target.getId());
            clearInvocations(productQueryRepository);
            assertThat(detail(target.getId()).likeCount()).isEqualTo(1);
            assertThat(itemOf(latestPage(), target.getId()).likeCount()).isEqualTo(1);
            assertServedFromCache();

            productLikeFacade.unlike(user.getId(), target.getId());
            assertThat(detail(target.getId()).likeCount()).isZero();
            assertThat(itemOf(latestPage(), target.getId()).likeCount()).isZero();
            assertServedFromCache();
        }
    }

    @Nested
    @DisplayName("DB 변경 rollback")
    class Rollback {
        @DisplayName("[DR-37] 커밋 전에 롤백된 수정은 revision 도 그대로라, 변경 전 결과를 캐시에서 읽고 새 revision 의 키는 생기지 않는다.")
        @Test
        void rolledBackUpdate_keepsPreviousResult() {
            ProductModel target = fixtures.product(brand.getId(), "상품", 1000L, 10);
            detail(target.getId());
            latestPage();
            long listRevision = fixtures.listRevision();

            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                productFacade.updateProduct(admin.getId(), target.getId(), "새 이름", 9000L);
                throw new IllegalStateException("커밋 전 실패");
            })).isInstanceOf(IllegalStateException.class);
            clearInvocations(productQueryRepository);

            assertThat(detail(target.getId()).name()).isEqualTo("상품");
            assertThat(itemOf(latestPage(), target.getId()).price()).isEqualTo(1000L);
            assertServedFromCache();
            assertThat(redisTemplate.hasKey(detailKey(target.getId(), 1, 0))).isFalse();
            assertThat(redisTemplate.hasKey(latestPageKey(listRevision + 1))).isFalse();
        }
    }

    @Nested
    @DisplayName("이전 조회의 늦은 캐시 채움")
    class LateFill {
        @DisplayName("[DR-37] 수정 전에 읽은 조회가 수정 커밋 뒤에야 옛 revision 키를 채워도, 이후 조회는 새 revision 키의 새 값을 읽는다. 옛 키에는 TTL 이 있다.")
        @Test
        void lateDetailFill_doesNotReachNewReaders() throws Exception {
            ProductModel target = fixtures.product(brand.getId(), "상품", 1000L, 10);
            CountDownLatch loaded = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                loaded.countDown();
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(productCache).putDetail(eq(target.getId()), eq(0L), anyLong(), any());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<ProductView.Summary> slowReader = executor.submit(() -> detail(target.getId()));
                assertThat(loaded.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

                productFacade.updateProduct(admin.getId(), target.getId(), "새 이름", 1000L);
                assertThat(detail(target.getId()).name()).isEqualTo("새 이름");
                release.countDown();
                assertThat(slowReader.get(WAIT_SECONDS, TimeUnit.SECONDS).name()).isEqualTo("상품");
            } finally {
                release.countDown();
                executor.shutdownNow();
            }

            String staleKey = detailKey(target.getId(), 0, 0);
            assertThat(redisTemplate.opsForValue().get(staleKey)).contains("\"상품\"");
            assertThat(redisTemplate.getExpire(staleKey)).isPositive();
            clearInvocations(productQueryRepository);
            assertThat(detail(target.getId()).name()).isEqualTo("새 이름");
            assertServedFromCache();
        }

        @DisplayName("[DR-37] 목록도 같다. 상품 생성 전에 읽은 첫 페이지가 늦게 옛 revision 키를 채워도, 이후 조회는 새 상품이 든 첫 페이지를 읽는다.")
        @Test
        void lateLatestPageFill_doesNotReachNewReaders() throws Exception {
            fixtures.product(brand.getId(), "상품", 1000L, 10);
            long staleRevision = fixtures.listRevision();
            CountDownLatch loaded = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                loaded.countDown();
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                return invocation.callRealMethod();
            }).when(productCache).putLatestPage(eq(staleRevision), any());
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Long createdId;
            try {
                Future<PageResult<ProductView.Summary>> slowReader = executor.submit(ProductCacheIntegrationTest.this::latestPage);
                assertThat(loaded.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

                createdId = productFacade.createProduct(admin.getId(), brand.getId(), "새 상품", 1000L, 10).id();
                assertThat(latestPage().items().get(0).id()).isEqualTo(createdId);
                release.countDown();
                assertThat(slowReader.get(WAIT_SECONDS, TimeUnit.SECONDS).totalCount()).isEqualTo(1);
            } finally {
                release.countDown();
                executor.shutdownNow();
            }

            assertThat(redisTemplate.hasKey(latestPageKey(staleRevision))).isTrue();
            assertThat(redisTemplate.getExpire(latestPageKey(staleRevision))).isPositive();
            clearInvocations(productQueryRepository);
            PageResult<ProductView.Summary> page = latestPage();
            assertThat(page.items().get(0).id()).isEqualTo(createdId);
            assertThat(page.totalCount()).isEqualTo(2);
            assertServedFromCache();
        }

        @DisplayName("[DR-37] miss 에서 revision 을 읽은 뒤 결과를 읽기 전에 수정이 커밋돼도, 결과는 그 revision 시점의 것이라 옛 키에 옛 값이 들어간다.")
        @Test
        void missReadsRevisionAndResultFromOneSnapshot() throws Exception {
            ProductModel target = fixtures.product(brand.getId(), "상품", 1000L, 10);
            AtomicBoolean first = new AtomicBoolean(true);
            ExecutorService writer = Executors.newSingleThreadExecutor();
            doAnswer(invocation -> {
                if (first.getAndSet(false)) {
                    writer.submit(() -> productFacade.updateProduct(admin.getId(), target.getId(), "새 이름", 1000L))
                        .get(WAIT_SECONDS, TimeUnit.SECONDS);
                }
                return invocation.callRealMethod();
            }).when(productQueryRepository).findActive(target.getId());
            try {
                assertThat(detail(target.getId()).name()).isEqualTo("상품");
            } finally {
                writer.shutdownNow();
            }

            assertThat(fixtures.reloadProduct(target.getId()).getRevision()).isEqualTo(1);
            assertThat(redisTemplate.opsForValue().get(detailKey(target.getId(), 0, 0))).contains("\"상품\"");
            assertThat(detail(target.getId()).name()).isEqualTo("새 이름");
        }
    }
}
