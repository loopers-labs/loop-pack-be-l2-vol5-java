package com.loopers.support.fixture;

import com.loopers.domain.catalog.BrandModel;
import com.loopers.domain.catalog.BrandRepository;
import com.loopers.domain.catalog.ProductLikeModel;
import com.loopers.domain.catalog.ProductLikeRepository;
import com.loopers.domain.catalog.ProductModel;
import com.loopers.domain.catalog.ProductRepository;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserRepository;
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

    public ProductModel product(Long brandId, String name, long price, int stock) {
        return productRepository.save(new ProductModel(brandId, name, price, stock));
    }

    @Transactional
    public ProductModel deletedProduct(Long brandId, String name, long price, int stock) {
        ProductModel product = productRepository.save(new ProductModel(brandId, name, price, stock));
        product.delete();
        return product;
    }

    public ProductLikeModel like(Long userId, Long productId) {
        return productLikeRepository.save(new ProductLikeModel(userId, productId));
    }

    public ProductModel reloadProduct(Long productId) {
        return productRepository.find(productId).orElseThrow();
    }

    public BrandModel reloadBrand(Long brandId) {
        return brandRepository.find(brandId).orElseThrow();
    }
}
