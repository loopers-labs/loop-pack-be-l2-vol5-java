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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("좋아요 상품 조회용 단일 인덱스와 기존 사용자·상품 유니크 인덱스를 함께 유지한다.")
    void keepsProductIndexAlongsideUserProductUniqueIndex() {
        assertThat(jdbcTemplate.queryForList("""
            SELECT COLUMN_NAME
            FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'likes'
              AND INDEX_NAME = 'idx_likes_product_id'
            ORDER BY SEQ_IN_INDEX
            """, String.class)).containsExactly("product_id");
        assertThat(jdbcTemplate.queryForObject("""
            SELECT NON_UNIQUE
            FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'likes'
              AND INDEX_NAME = 'idx_likes_product_id'
            """, Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
            SELECT COLUMN_NAME
            FROM information_schema.statistics
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'likes'
              AND INDEX_NAME = 'uk_like_user_product' AND NON_UNIQUE = 0
            ORDER BY SEQ_IN_INDEX
            """, String.class)).containsExactly("user_id", "product_id");
    }

    @Test
    @DisplayName("요청한 상품들의 좋아요 수를 상품별로 집계하고, 중복 ID로 수가 늘어나지 않는다.")
    void countsLikesByRequestedProductIds() {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        Product first = productRepository.save(Product.create(brand.getId(), "First", 100L));
        Product second = productRepository.save(Product.create(brand.getId(), "Second", 200L));
        Product notLiked = productRepository.save(Product.create(brand.getId(), "Not liked", 300L));
        Product outside = productRepository.save(Product.create(brand.getId(), "Outside", 400L));
        likeRepository.save(Like.create(1L, first.getId()));
        likeRepository.save(Like.create(2L, first.getId()));
        likeRepository.save(Like.create(1L, second.getId()));
        likeRepository.save(Like.create(1L, outside.getId()));
        entityManager.flush();
        entityManager.clear();

        Map<Long, Long> counts = likeRepository.countByProductIds(
            List.of(first.getId(), second.getId(), notLiked.getId(), first.getId())
        );

        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(first.getId(), 2L, second.getId(), 1L));
    }

    @Test
    @DisplayName("상품 ID 목록이 비어 있으면 좋아요 집계 결과도 비어 있다.")
    void returnsEmptyLikeCountsForEmptyProductIds() {
        assertThat(likeRepository.countByProductIds(List.of())).isEmpty();
    }

    @ParameterizedTest(name = "페이지 상품 수={0}")
    @ValueSource(ints = {1, 20, 100})
    @DisplayName("상품 한 개부터 최대 페이지 크기까지 실제 DB의 좋아요 수를 정확히 조회한다.")
    void countsLikesForSupportedPageSizes(int size) {
        Brand brand = brandRepository.save(Brand.create("Nike"));
        List<Long> productIds = new ArrayList<>();
        Map<Long, Long> expected = new HashMap<>();
        for (int index = 0; index < size; index++) {
            Product product = productRepository.save(Product.create(brand.getId(), "Product " + index, 100L));
            productIds.add(product.getId());
            if (index % 3 != 0) {
                likeRepository.save(Like.create(1L, product.getId()));
                expected.put(product.getId(), 1L);
            }
        }
        entityManager.flush();
        entityManager.clear();

        assertThat(likeRepository.countByProductIds(productIds)).containsExactlyInAnyOrderEntriesOf(expected);
    }

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

        reloadedOrder.confirm();
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
