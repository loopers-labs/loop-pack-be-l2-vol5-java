package com.loopers.testsupport;

import com.loopers.application.purchaseactivity.PurchaseActivityHandler;
import com.loopers.application.purchaseactivity.PurchaseActivityProbe;
import com.loopers.config.jpa.JpaConfig;
import com.loopers.infrastructure.purchaseactivity.PurchaseActivityRepositoryImpl;
import com.loopers.interfaces.consumer.order.OrderConfirmedParser;
import com.loopers.support.persistence.PurchaseActivityConstraintClassifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.util.function.LongConsumer;

/**
 * 구매 활동 DB 검증(C2~C5)의 짧은 테스트 컨텍스트. 기존 MySQL Testcontainers fixture 의 같은 DB 에 붙고,
 * 공통 JPA 설정과 구매 활동 bean·parser 만 올린다. 상위 패키지의 demo listener·Kafka·Redis 설정은 스캔하지 않는다.
 * ddl-auto=create(test 프로필)로 이 컨텍스트의 두 Entity 테이블만 만든다.
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({
    HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@ComponentScan(basePackageClasses = {
    JpaConfig.class, PurchaseActivityHandler.class, PurchaseActivityRepositoryImpl.class,
    PurchaseActivityConstraintClassifier.class, OrderConfirmedParser.class})
public class PurchaseActivityDbTestConfig {

    public static final String PROCESSED = "processed_order_confirmation";
    public static final String ACTIVITY = "product_purchase_activity";

    /**
     * datasource 바인딩보다 먼저 기존 fixture 를 기동해 JDBC 시스템 속성을 둔다. static BFPP 는 일반 bean 생성 전에 호출된다.
     * 속성이 없으면 기본 placeholder 로 연결에 실패할 뿐 다른 DB 로 연결하지 않는다.
     */
    @Bean
    static BeanFactoryPostProcessor mySqlFixtureFirst() throws ClassNotFoundException {
        Class.forName("com.loopers.testcontainers.MySqlTestContainersConfig");
        return beanFactory -> { };
    }

    @Bean
    @Primary
    ControllableProbe controllableProbe() {
        return new ControllableProbe();
    }

    /** writer 가 없는 상태에서 호출한다. 두 구매 테이블을 비우고 0행을 확인한다. API 테이블은 이 DB 에 만들지 않는다. */
    public static void resetTables(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE TABLE " + PROCESSED);
        jdbc.execute("TRUNCATE TABLE " + ACTIVITY);
        Integer processed = jdbc.queryForObject("SELECT COUNT(*) FROM " + PROCESSED, Integer.class);
        Integer activity = jdbc.queryForObject("SELECT COUNT(*) FROM " + ACTIVITY, Integer.class);
        if (processed == null || processed != 0 || activity == null || activity != 0) {
            throw new IllegalStateException("구매 테이블 정리 뒤 0행이 아닙니다.");
        }
    }

    /**
     * 잠금 대기·미commit 행 관찰 전용 root 연결. 테스트 계정에는 performance_schema·INNODB_TRX 조회 권한이 없다.
     * 읽기만 하며 스키마·권한·데이터를 바꾸지 않는다.
     */
    public static SingleConnectionDataSource observer() {
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        return new SingleConnectionDataSource(url, "root", System.getProperty("datasource.mysql-jpa.main.password"), true);
    }

    /** 처리 TX 의 중간 지점에 테스트 동작을 넣는다. 기본은 아무것도 하지 않으며 각 테스트가 끝나면 reset 한다. */
    public static class ControllableProbe implements PurchaseActivityProbe {

        private volatile ActivityHook afterActivitySaved = (orderId, productId) -> { };
        private volatile LongConsumer beforeCommit = orderId -> { };

        public void onAfterActivitySaved(ActivityHook hook) {
            this.afterActivitySaved = hook;
        }

        public void onBeforeCommit(LongConsumer hook) {
            this.beforeCommit = hook;
        }

        public void reset() {
            this.afterActivitySaved = (orderId, productId) -> { };
            this.beforeCommit = orderId -> { };
        }

        @Override
        public void afterActivitySaved(long orderId, long productId) {
            afterActivitySaved.accept(orderId, productId);
        }

        @Override
        public void beforeCommit(long orderId) {
            beforeCommit.accept(orderId);
        }
    }

    @FunctionalInterface
    public interface ActivityHook {
        void accept(long orderId, long productId);
    }
}
