package com.loopers.application.order;

import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 목록이 품목을 주문마다 따로 읽지 않는지(N+1) 실행된 SQL 문 수로 확인한다.
 * 품목은 지연 로딩이지만 batch fetch(jpa.yml 의 default_batch_fetch_size)로 한 번에 읽어야 한다.
 * 통계는 이 클래스에서만 켠다.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class OrderListQueryCountTest {

    // 주문 페이지 1회 + 총 개수 1회 + 품목 일괄 조회 1회. 주문 수에 비례하면 안 된다.
    // (default_batch_fetch_size 를 1 로 덮어 확인하면 주문 4건에서 6회가 되어 이 테스트가 실패한다.)
    private static final int EXPECTED_STATEMENTS = 3;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private AdminOrderFacade adminOrderFacade;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private User prepareOrdersWithManyItems(int orderCount) {
        User user = userRepository.save(User.create());
        List<Product> products = List.of(
            productRepository.save(Product.create(1L, "티셔츠", new Money(3_000), new Stock(100))),
            productRepository.save(Product.create(1L, "양말", new Money(2_000), new Stock(100))),
            productRepository.save(Product.create(1L, "모자", new Money(5_000), new Stock(100)))
        );
        for (int i = 0; i < orderCount; i++) {
            orderFacade.create(
                user.getId(),
                products.stream().map(product -> new OrderItemCommand(product.getId(), 1)).toList()
            );
        }
        return user;
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        return statistics;
    }

    @DisplayName("내 주문 목록은 주문 수와 상관없이 정해진 횟수의 SQL 로 품목 수까지 조회한다.")
    @ParameterizedTest
    @ValueSource(ints = {3, 8})
    void myList_doesNotQueryPerOrder(int orderCount) {
        // arrange: 품목이 3개인 주문. 총 개수 쿼리까지 실행되도록 페이지 크기를 주문 수보다 1 작게 잡는다.
        User user = prepareOrdersWithManyItems(orderCount);
        Statistics statistics = statistics();

        // act
        var page = orderFacade.getMyList(user.getId(), 0, orderCount - 1);

        // assert
        assertThat(page.getContent()).hasSize(orderCount - 1);
        assertThat(page.getContent()).allMatch(order -> order.itemCount() == 3);
        assertThat(statistics.getPrepareStatementCount()).as("실행된 SQL 수").isEqualTo(EXPECTED_STATEMENTS);
    }

    @DisplayName("관리자 주문 목록도 주문 수와 상관없이 정해진 횟수의 SQL 로 조회한다.")
    @ParameterizedTest
    @ValueSource(ints = {3, 8})
    void adminList_doesNotQueryPerOrder(int orderCount) {
        // arrange
        prepareOrdersWithManyItems(orderCount);
        Statistics statistics = statistics();

        // act
        var page = adminOrderFacade.getList(null, 0, orderCount - 1);

        // assert
        assertThat(page.getContent()).hasSize(orderCount - 1);
        assertThat(page.getContent()).allMatch(order -> order.itemCount() == 3);
        assertThat(statistics.getPrepareStatementCount()).as("실행된 SQL 수").isEqualTo(EXPECTED_STATEMENTS);
    }
}
