package com.loopers.support.fixture;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.CatalogRevisionModel;
import com.loopers.domain.productlike.ProductLikeModel;
import com.loopers.domain.productlike.ProductLikeRepository;
import com.loopers.domain.productlike.ProductStatModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductStockLedgerModel;
import com.loopers.domain.product.ProductStockModel;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테스트 fixture. 사용자·관리자는 런타임 생성 FR 이 없어 여기서만 만들어진다 (DR-12).
 */
@Component
public class Fixtures {
    private final UserRepository userRepository;
    private final BrandRepository brandRepository;
    private final ProductRepository productRepository;
    private final ProductLikeRepository productLikeRepository;
    private final PointRepository pointRepository;
    private final OrderRepository orderRepository;
    @PersistenceContext
    private EntityManager entityManager;

    public Fixtures(
        UserRepository userRepository,
        BrandRepository brandRepository,
        ProductRepository productRepository,
        ProductLikeRepository productLikeRepository,
        PointRepository pointRepository,
        OrderRepository orderRepository
    ) {
        this.userRepository = userRepository;
        this.brandRepository = brandRepository;
        this.productRepository = productRepository;
        this.productLikeRepository = productLikeRepository;
        this.pointRepository = pointRepository;
        this.orderRepository = orderRepository;
    }

    /**
     * TB-09 목록 revision 행 (DR-37). 운영에서는 배포 SQL 로 만들고, 테스트는 truncate 뒤마다 다시 만든다.
     * 상품·브랜드 쓰기 Facade 나 고객 상품 목록을 부르는 테스트는 @BeforeEach 에서 먼저 부른다. 빠뜨리면 쓰기가 실패한다.
     */
    @Transactional
    public CatalogRevisionModel catalogRevision() {
        CatalogRevisionModel revision = new CatalogRevisionModel(CatalogRevisionModel.PRODUCT_LIST_LATEST);
        entityManager.persist(revision);
        return revision;
    }

    /** TB-09 latest 첫 페이지 목록의 현재 revision (DR-37). */
    public long listRevision() {
        return entityManager.createQuery(
                "select r.revision from CatalogRevisionModel r where r.name = :name", Long.class)
            .setParameter("name", CatalogRevisionModel.PRODUCT_LIST_LATEST)
            .getSingleResult();
    }

    /** DRAFT 주문. 단가는 상품의 현재 가격 사본. */
    public OrderModel draftOrder(Long userId, ProductModel product, int quantity) {
        return orderRepository.save(OrderModel.create(userId,
            java.util.List.of(new OrderModel.Line(product.getId(), quantity, product.getPrice()))));
    }

    @Transactional
    public OrderModel confirmedOrder(Long userId, ProductModel product, int quantity) {
        OrderModel order = orderRepository.save(OrderModel.create(userId,
            java.util.List.of(new OrderModel.Line(product.getId(), quantity, product.getPrice()))));
        order.confirm();
        return order;
    }

    @Transactional(readOnly = true)
    public OrderModel reloadOrder(Long orderId) {
        OrderModel order = orderRepository.find(orderId).orElseThrow();
        order.getItems().size();
        return order;
    }

    /** 사용자 + 잔액 0 의 포인트 계정 (DR-12). */
    public UserModel user() {
        return userWithBalance(0L);
    }

    public UserModel userWithBalance(long balance) {
        UserModel user = userRepository.save(new UserModel(false));
        pointRepository.save(new PointModel(user.getId(), balance));
        return user;
    }

    public UserModel admin() {
        UserModel admin = userRepository.save(new UserModel(true));
        pointRepository.save(new PointModel(admin.getId(), 0L));
        return admin;
    }

    public long balanceOf(Long userId) {
        return pointRepository.findByUserId(userId).orElseThrow().getBalance();
    }

    public BrandModel brand(String name) {
        return brandRepository.save(new BrandModel(name));
    }

    @Transactional
    public BrandModel deletedBrand(String name) {
        BrandModel brand = brandRepository.save(new BrandModel(name));
        brand.delete();
        return brand;
    }

    /** 상품 + 현재 재고 행과 초기 재고 ADJUSTMENT (DR-38) + 좋아요 수 0 인 행 (DR-36). */
    @Transactional
    public ProductModel product(Long brandId, String name, long price, int stock) {
        ProductModel product = productRepository.save(new ProductModel(brandId, name, price));
        ProductStockModel productStock = productRepository.saveStock(new ProductStockModel(product.getId()));
        productStock.adjustTo(stock).ifPresent(productRepository::saveLedger);
        productLikeRepository.saveStat(new ProductStatModel(product.getId(), brandId));
        return product;
    }

    @Transactional
    public ProductModel deletedProduct(Long brandId, String name, long price, int stock) {
        ProductModel product = product(brandId, name, price, stock);
        product.delete();
        productLikeRepository.deleteStat(product.getId());
        return product;
    }

    /** 관계 + 좋아요 수 +1 (INV-05, DR-36). */
    @Transactional
    public ProductLikeModel like(Long userId, Long productId) {
        ProductLikeModel like = productLikeRepository.save(new ProductLikeModel(userId, productId));
        productLikeRepository.increaseLikeCount(productId);
        return like;
    }

    public ProductStatModel reloadStat(Long productId) {
        return productLikeRepository.findStat(productId).orElseThrow();
    }

    /** TB-10 현재 재고 (DR-38). */
    public int stockOf(Long productId) {
        return productRepository.findStock(productId).orElseThrow().getAvailable();
    }

    /** TB-11 원장, 쌓인 순서대로 (DR-38). */
    public java.util.List<ProductStockLedgerModel> ledgerOf(Long productId) {
        return entityManager.createQuery(
                "select l from ProductStockLedgerModel l where l.productId = :productId order by l.id", ProductStockLedgerModel.class)
            .setParameter("productId", productId)
            .getResultList();
    }

    public ProductModel reloadProduct(Long productId) {
        return productRepository.find(productId).orElseThrow();
    }

    public BrandModel reloadBrand(Long brandId) {
        return brandRepository.find(brandId).orElseThrow();
    }
}
