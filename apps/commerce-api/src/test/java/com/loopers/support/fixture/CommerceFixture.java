package com.loopers.support.fixture;

import com.loopers.brand.domain.Brand;
import com.loopers.like.domain.Like;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.product.domain.Product;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * HTTP 테스트가 요청 전에 준비하는 저장 데이터다. 준비마다 트랜잭션을 따로 커밋한다.
 */
public class CommerceFixture {

    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;

    public CommerceFixture(EntityManager entityManager, PlatformTransactionManager transactionManager) {
        this.entityManager = entityManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public User user() {
        return persist(new User());
    }

    public User userWithPoint(long balance) {
        User user = new User();
        if (balance > 0) {
            user.charge(balance);
        }
        return persist(user);
    }

    public Brand brand(String name) {
        return persist(new Brand(name));
    }

    public Brand deletedBrand(String name) {
        Brand brand = new Brand(name);
        brand.delete();
        return persist(brand);
    }

    public Product product(Brand brand, String name, long price, int stock) {
        Product product = new Product(brand.getId(), name, price);
        product.changeStock(stock);
        return persist(product);
    }

    public Product deletedProduct(Brand brand, String name, long price) {
        Product product = new Product(brand.getId(), name, price);
        product.delete();
        return persist(product);
    }

    public Like like(User user, Product product) {
        return persist(new Like(user.getId(), product.getId()));
    }

    public OrderItem item(Product product, int quantity) {
        return OrderItem.of(product, quantity);
    }

    public Order draftOrder(User buyer, OrderItem... items) {
        return persist(new Order(buyer.getId(), List.of(items)));
    }

    public Order confirmedOrder(User buyer, OrderItem... items) {
        Order order = new Order(buyer.getId(), List.of(items));
        order.confirm(order.getTotalAmount(), ZonedDateTime.now());
        return persist(order);
    }

    public <T> void update(Class<T> type, Long id, Consumer<T> change) {
        transactionTemplate.executeWithoutResult(status -> change.accept(entityManager.find(type, id)));
    }

    /**
     * DatabaseCleanUp은 엔티티의 테이블만 비운다. 주문 품목 같은 값 컬렉션 테이블까지 남지 않도록 스키마의 모든 테이블을 비운다.
     */
    public void truncateRemainingTables() {
        transactionTemplate.executeWithoutResult(status -> {
            List<?> tables = entityManager.createNativeQuery(
                "select table_name from information_schema.tables "
                    + "where table_schema = database() and table_type = 'BASE TABLE'"
            ).getResultList();
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();
            for (Object table : tables) {
                entityManager.createNativeQuery("TRUNCATE TABLE `" + table + "`").executeUpdate();
            }
            entityManager.createNativeQuery("SET FOREIGN_KEY_CHECKS = 1").executeUpdate();
        });
    }

    private <T> T persist(T entity) {
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(entity));
        return entity;
    }
}
