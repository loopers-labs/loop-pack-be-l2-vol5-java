package com.loopers.application.product;

import com.loopers.application.brand.AdminBrandService;
import com.loopers.application.brand.BrandRegistrationService;
import com.loopers.application.like.LikeService;
import com.loopers.application.order.OrderService;
import com.loopers.application.user.PointService;
import com.loopers.application.user.UserResolutionException;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductException;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
class ProductListRevisionMutationIntegrationTest {

    @Autowired private AdminProductService adminProducts;
    @Autowired private AdminBrandService adminBrands;
    @Autowired private BrandRegistrationService brandRegistration;
    @Autowired private LikeService likes;
    @Autowired private OrderService orders;
    @Autowired private PointService points;
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ProductListRevisionRepository revisions;

    private long brandId;
    private long productId;
    private long orderId;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        initializer.initialize();
        Brand brand = brands.save(new Brand("브랜드"));
        brandId = brand.getId();
        productId = products.save(new Product(brand, "상품", 100, 5)).getId();
        products.save(new Product(brand, "품절 상품", 200, 0));
        products.save(new Product(brands.save(new Brand("다른 브랜드")), "다른 상품", 300, 7));
        points.charge("alice", 1000);
        orderId = orders.create("alice", List.of(new OrderQuantities.Item(productId, 2))).orderId();
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @AfterEach
    void tearDown() {
        cleanup.truncateAllTables();
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void advancesExactlyOnceForEveryVisibleMutation(Mutation mutation) {
        prepare(mutation);
        long before = revisions.current();

        mutate(mutation);

        assertThat(revisions.current()).isEqualTo(before + 1);
        assertCommittedMutation(mutation);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void rollsBackBusinessSqlAndRevisionTogetherWhenCallerFails(Mutation mutation) {
        prepare(mutation);
        long beforeRevision = revisions.current();
        var before = businessState();
        IllegalStateException failure = new IllegalStateException("업무와 버전 SQL 뒤 실패");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            mutate(mutation);
            entityManager.flush();
            assertThat(revisions.current()).isEqualTo(beforeRevision + 1);
            assertThat(businessState()).isNotEqualTo(before);
            throw failure;
        })).isSameAs(failure);

        assertThat(revisions.current()).isEqualTo(beforeRevision);
        assertThat(businessState()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Mutation.class)
    void revisionFailureRollsBackEveryBusinessMutation(Mutation mutation) {
        prepare(mutation);
        long beforeRevision = revisions.current();
        var before = businessState();
        IllegalStateException failure = new IllegalStateException("버전 저장 경계 실패");
        ProductListRevisionRepository target = AopTestUtils.getUltimateTargetObject(revisions);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(revisions.current()).isEqualTo(beforeRevision + 1);
            assertThat(businessState()).isNotEqualTo(before);
            throw failure;
        }).when(target).advance();

        assertThatThrownBy(() -> mutate(mutation))
            .isInstanceOf(InvalidDataAccessApiUsageException.class)
            .hasCause(failure);

        assertThat(revisions.current()).isEqualTo(beforeRevision);
        assertThat(businessState()).isEqualTo(before);
    }

    @Test
    void preservesRevisionForUnchangedNamesPricesStockAndLikeRequests() {
        long before = revisions.current();
        adminProducts.update(UserRole.ADMIN, productId, " 상품 ", 100);
        assertThat(revisions.current()).isEqualTo(before);
        adminProducts.changeStock(UserRole.ADMIN, productId, 5);
        assertThat(revisions.current()).isEqualTo(before);
        adminBrands.rename(UserRole.ADMIN, brandId, " 브랜드 ");
        assertThat(revisions.current()).isEqualTo(before);
        likes.cancel("alice", productId);
        assertThat(revisions.current()).isEqualTo(before);

        likes.register("alice", productId);
        long registered = revisions.current();
        likes.register("alice", productId);
        assertThat(revisions.current()).isEqualTo(registered);
    }

    @Test
    void preservesRevisionForRepeatedDeletionAndDeletedProductLikeCancellation() {
        likes.register("alice", productId);
        adminProducts.delete(UserRole.ADMIN, productId);
        long deletedProduct = revisions.current();

        adminProducts.delete(UserRole.ADMIN, productId);
        assertThat(revisions.current()).isEqualTo(deletedProduct);
        likes.cancel("alice", productId);
        assertThat(revisions.current()).isEqualTo(deletedProduct);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isZero();

        adminBrands.delete(UserRole.ADMIN, brandId);
        long deletedBrand = revisions.current();
        adminBrands.delete(UserRole.ADMIN, brandId);
        assertThat(revisions.current()).isEqualTo(deletedBrand);
    }

    @Test
    void keepsPointChargeDraftCreationAndEmptyBrandRegistrationOutsideRevision() {
        long before = revisions.current();

        points.charge("alice", 500);
        orders.create("alice", List.of(new OrderQuantities.Item(productId, 1)));
        brandRegistration.register(UserRole.ADMIN, "상품 없는 브랜드");

        assertThat(revisions.current()).isEqualTo(before);
        assertThat(points.balance("alice").balance()).isEqualTo(1500);
    }

    @Test
    void rejectedMutationPreservesRevisionAndData() {
        long beforeRevision = revisions.current();
        var before = businessState();

        assertThatThrownBy(() -> adminProducts.update(UserRole.ADMIN, productId, "변경", 0))
            .isInstanceOf(ProductException.class);
        assertThatThrownBy(() -> adminProducts.changeStock(UserRole.ADMIN, productId, -1))
            .isInstanceOf(ProductStockException.class);
        assertThatThrownBy(() -> adminProducts.delete(UserRole.CUSTOMER, productId))
            .isInstanceOf(UserResolutionException.class);

        assertThat(revisions.current()).isEqualTo(beforeRevision);
        assertThat(businessState()).isEqualTo(before);
    }

    private void prepare(Mutation mutation) {
        if (mutation == Mutation.CANCEL_LIKE) {
            likes.register("alice", productId);
        }
    }

    private void mutate(Mutation mutation) {
        switch (mutation) {
            case CREATE_PRODUCT -> adminProducts.create(UserRole.ADMIN, brandId, "새 상품", 500, 4);
            case UPDATE_PRODUCT -> adminProducts.update(UserRole.ADMIN, productId, "수정 상품", 500);
            case CHANGE_STOCK -> adminProducts.changeStock(UserRole.ADMIN, productId, 9);
            case DELETE_PRODUCT -> adminProducts.delete(UserRole.ADMIN, productId);
            case RENAME_BRAND -> adminBrands.rename(UserRole.ADMIN, brandId, "수정 브랜드");
            case DELETE_BRAND -> adminBrands.delete(UserRole.ADMIN, brandId);
            case REGISTER_LIKE -> likes.register("alice", productId);
            case CANCEL_LIKE -> likes.cancel("alice", productId);
            case CONFIRM_ORDER -> orders.confirm("alice", orderId);
        }
    }

    private void assertCommittedMutation(Mutation mutation) {
        switch (mutation) {
            case CREATE_PRODUCT -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class))
                .isEqualTo(4);
            case UPDATE_PRODUCT -> {
                Product product = products.findById(productId).orElseThrow();
                assertThat(product.getName()).isEqualTo("수정 상품");
                assertThat(product.getPrice()).isEqualTo(500);
            }
            case CHANGE_STOCK -> assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(9);
            case DELETE_PRODUCT -> assertThat(products.findById(productId).orElseThrow().isDeleted()).isTrue();
            case RENAME_BRAND -> assertThat(brands.findById(brandId).orElseThrow().getName()).isEqualTo("수정 브랜드");
            case DELETE_BRAND -> {
                assertThat(brands.findById(brandId).orElseThrow().isDeleted()).isTrue();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE deleted_at IS NOT NULL", Long.class))
                    .isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE deleted_at IS NULL", Long.class))
                    .isEqualTo(1);
            }
            case REGISTER_LIKE -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(1);
            case CANCEL_LIKE -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isZero();
            case CONFIRM_ORDER -> {
                assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(3);
                assertThat(points.balance("alice").balance()).isEqualTo(800);
                assertThat(jdbc.queryForObject("SELECT status FROM `order` WHERE id = ?", String.class, orderId))
                    .isEqualTo("CONFIRMED");
            }
        }
    }

    private Map<String, List<Map<String, Object>>> businessState() {
        return Map.of("brands", jdbc.queryForList("SELECT * FROM brand ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "likes", jdbc.queryForList("SELECT * FROM `like` ORDER BY id"),
            "orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"));
    }

    enum Mutation {
        CREATE_PRODUCT, UPDATE_PRODUCT, CHANGE_STOCK, DELETE_PRODUCT, RENAME_BRAND,
        DELETE_BRAND, REGISTER_LIKE, CANCEL_LIKE, CONFIRM_ORDER
    }
}
