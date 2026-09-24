package com.loopers.infrastructure.mall.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.mall.brand.Brand;
import com.loopers.domain.mall.brand.BrandRepository;
import com.loopers.domain.mall.product.Product;
import com.loopers.domain.mall.product.ProductRepository;
import com.loopers.support.concurrency.ConcurrentRequests;
import com.loopers.support.concurrency.ConcurrentRequests.Outcome;
import com.loopers.utils.DatabaseCleanUp;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
// 제품 코드를 거치지 않고 잠금 없는 SELECT+상수 UPDATE로 갱신 유실을 재현하는 대조군
class StockLostUpdateControlGroupTest {
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("잠금 없는 SELECT 뒤 상수로 덮어쓰면 두 트랜잭션 모두 성공해도 수량식이 어긋난다")
    @Test
    void reproducesLostUpdate_withoutLocking() throws Exception {
        long productId = brandAndProduct(5);
        CyclicBarrier bothReadDone = new CyclicBarrier(2);

        List<Callable<Boolean>> tasks = List.of(
            () -> readThenWriteConstant(productId, bothReadDone),
            () -> readThenWriteConstant(productId, bothReadDone)
        );
        List<Outcome<Boolean>> results = ConcurrentRequests.run(tasks, 10, 30);

        long successCount = results.stream().filter(Outcome::isSuccess).count();
        int finalStock = readStock(productId);

        assertThat(successCount).isEqualTo(2);
        assertThat(finalStock).isEqualTo(4);
        assertThat(successCount + finalStock).isNotEqualTo(5); // 성공 2 + 최종 재고 4 = 6 ≠ 5, 갱신 유실 증거
    }

    // 잠금 없이 재고를 읽어 5임을 확인하고, 양쪽 다 읽을 때까지 대기한 뒤 상수 4로 덮어쓰고 커밋
    private boolean readThenWriteConstant(long productId, CyclicBarrier bothReadDone) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int read = readStock(connection, productId);
            if (read != 5) {
                connection.rollback();
                return false;
            }
            bothReadDone.await(10, java.util.concurrent.TimeUnit.SECONDS);
            try (PreparedStatement update = connection.prepareStatement("UPDATE products SET stock = 4 WHERE id = ?")) {
                update.setLong(1, productId);
                update.executeUpdate();
            }
            connection.commit();
            return true;
        }
    }

    private long brandAndProduct(int stock) {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        return productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, stock)).getId();
    }

    private int readStock(long productId) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            return readStock(connection, productId);
        }
    }

    private int readStock(Connection connection, long productId) throws Exception {
        try (PreparedStatement select = connection.prepareStatement("SELECT stock FROM products WHERE id = ?")) {
            select.setLong(1, productId);
            try (ResultSet resultSet = select.executeQuery()) {
                resultSet.next();
                return resultSet.getInt("stock");
            }
        }
    }
}
