package com.loopers.infrastructure;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.Like;
import com.loopers.domain.like.LikeRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class OtherDomainRepositoryIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LikeRepository likeRepository;

    @Autowired
    private PointRepository pointRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private ProductRepository productRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void persistsAndReconstitutesUserLikePointAndOrder() {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product product = productRepository.save(Product.create(brand.getId(), "Air Max", 100L));
        Like like = likeRepository.save(Like.create(user.getId(), product.getId()));
        Point point = pointRepository.save(Point.create(user.getId()));
        point.charge(300L);
        pointRepository.save(point);
        Order order = orderRepository.save(Order.create(user.getId(), List.of(
            OrderItem.create(product.getId(), product.getName(), product.getPrice(), 2)
        )));
        entityManager.flush();
        entityManager.clear();

        assertThat(userRepository.existsById(user.getId())).isTrue();
        assertThat(likeRepository.findByUserIdAndProductId(user.getId(), product.getId()))
            .map(Like::getProductId)
            .contains(product.getId());
        assertThat(pointRepository.findByUserId(user.getId()).orElseThrow().getBalance().amount()).isEqualTo(300L);

        Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reloadedOrder.getStatus().name()).isEqualTo("DRAFT");
        assertThat(reloadedOrder.getTotalAmount()).isEqualTo(200L);
        assertThat(reloadedOrder.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getProductId()).isEqualTo(product.getId());
            assertThat(item.getProductName()).isEqualTo("Air Max");
            assertThat(item.getUnitPrice()).isEqualTo(100L);
            assertThat(item.getQuantity()).isEqualTo(2);
        });

        reloadedOrder.confirm(reloadedOrder.getTotalAmount());
        orderRepository.save(reloadedOrder);
        entityManager.flush();
        entityManager.clear();

        Order confirmedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(confirmedOrder.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(confirmedOrder.getItems()).hasSize(1);
        likeRepository.delete(like);
        assertThat(likeRepository.findByUserIdAndProductId(user.getId(), product.getId())).isEmpty();
    }
}
