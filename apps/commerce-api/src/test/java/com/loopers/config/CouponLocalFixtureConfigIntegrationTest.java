package com.loopers.config;

import com.loopers.CommerceApiApplication;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.application.coupon.CouponIssueResult;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisProperties;
import com.loopers.fixture.CouponRedisTarget;
import com.loopers.infrastructure.coupon.CouponAdmissionRedisRepository;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 직접 실행용 준비 코드(설계 §9, Q15 의 준비 옵션 부분). 실제 앱 시작을 test 에서 직접 수행해 옵션별 등록·실행, 같은 DB 를 보존한
 * 재시작(ddl-auto=update), 준비 TX 중간 실패의 rollback·기동 실패 전파를 확인한다. 공통 기본 ddl-auto 는 바꾸지 않고 시작 인자로 고른다.
 * 각 시작은 test 프로필·fixture MySQL·fixture Redis 주소를 쓰고, 검증 뒤 컨텍스트를 닫는다. 같은 컨텍스트에서 Runner 를 다시 호출하는
 * 확인은 재시작 확인과 별개로 기록한다.
 *
 * <p>여러 시작이 같은 fixture MySQL 을 공유하고 그 DB 는 같은 JVM 의 다른 테스트 컨텍스트도 쓴다. 모든 시작은 동기 실행이며 닫힌 뒤
 * 남는 작업이 없으므로, 사례가 끝나면 한 번 더 시작해 이 테스트가 남긴 행을 비운다.
 */
@DisplayName("로컬 준비는 옵션을 명시한 시작에서만 한 TX 로 새 데이터를 추가하고, 기존 데이터·수용 상태를 바꾸지 않으며, 실패하면 rollback 후 기동을 멈춘다.")
class CouponLocalFixtureConfigIntegrationTest {

    private static final String RUNNER = "couponLocalFixtureRunner";

    @AfterEach
    void clearPreparedRows() {
        try (ConfigurableApplicationContext context = boot("--spring.jpa.hibernate.ddl-auto=update")) {
            context.getBean(DatabaseCleanUp.class).truncateAllTables();
        }
    }

    private static ConfigurableApplicationContext boot(List<ApplicationContextInitializer<ConfigurableApplicationContext>> initializers,
                                                       String... args) {
        List<String> all = new ArrayList<>(List.of(
            "--server.port=0", "--management.server.port=0",
            "--datasource.redis.database=0",
            "--datasource.redis.master.host=" + RedisTestContainersConfig.host(),
            "--datasource.redis.master.port=" + RedisTestContainersConfig.port(),
            "--datasource.redis.replicas[0].host=" + RedisTestContainersConfig.host(),
            "--datasource.redis.replicas[0].port=" + RedisTestContainersConfig.port()));
        all.addAll(List.of(args));
        SpringApplicationBuilder builder = new SpringApplicationBuilder(CommerceApiApplication.class).profiles("test");
        initializers.forEach(builder::initializers);
        return builder.run(all.toArray(String[]::new));
    }

    private static ConfigurableApplicationContext boot(String... args) {
        return boot(List.of(), args);
    }

    private record Counts(long users, long points, long campaigns, long coupons) {
        static Counts of(JdbcTemplate jdbc) {
            return new Counts(count(jdbc, "users"), count(jdbc, "point"), count(jdbc, "coupon_campaign"), count(jdbc, "coupon"));
        }

        private static long count(JdbcTemplate jdbc, String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        }
    }

    private static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = error; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    private static void assertNoFixture(ConfigurableApplicationContext context) {
        assertAll(
            () -> assertThat(context.containsBean(RUNNER)).isFalse(),
            () -> assertThat(context.getBeansOfType(CouponLocalFixtureConfig.class)).isEmpty()
        );
    }

    @SuppressWarnings("unchecked")
    private static RedisTemplate<String, String> master(ConfigurableApplicationContext context) {
        return (RedisTemplate<String, String>) BeanFactoryAnnotationUtils.qualifiedBeanOfType(
            context.getBeanFactory(), RedisTemplate.class, RedisConfig.REDIS_TEMPLATE_MASTER);
    }

    @DisplayName("생략·false 는 Runner 를 등록하지 않고 데이터를 늘리지 않는다. true 는 시작마다 한 번 새 사용자 8·Point 8·캠페인 1 을 추가하고, update 로 보존한 DB 의 기존 캠페인·쿠폰·발급 수·Point 는 그대로이며 기존 ID 로 발급할 수 있다. 수용은 계속 닫혀 있다.")
    @Test
    void optionGatesRunnerAndRestartAddsOnlyNewData() throws Exception {
        // 1. 옵션 생략 + create: 빈 스키마에서 시작. Runner 없음.
        try (ConfigurableApplicationContext context = boot("--spring.jpa.hibernate.ddl-auto=create")) {
            assertNoFixture(context);
            assertThat(Counts.of(context.getBean(JdbcTemplate.class))).isEqualTo(new Counts(0, 0, 0, 0));
        }

        // 2. 명시 false + update: Runner 없음, 데이터 증가 없음.
        try (ConfigurableApplicationContext context = boot("--spring.jpa.hibernate.ddl-auto=update", "--commerce.coupon.fixture.enabled=false")) {
            assertNoFixture(context);
            assertThat(Counts.of(context.getBean(JdbcTemplate.class))).isEqualTo(new Counts(0, 0, 0, 0));
        }

        // 3. true + update: 한 번 준비. 이후 발급 한 건으로 '기존 효과'를 만든다.
        Long firstCampaign;
        List<Long> firstUsers;
        Map<String, Object> issuedCoupon;
        try (ConfigurableApplicationContext context = boot("--spring.jpa.hibernate.ddl-auto=update", "--commerce.coupon.fixture.enabled=true")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            CouponFacade facade = context.getBean(CouponFacade.class);
            assertThat(context.containsBean(RUNNER)).isTrue();
            assertThat(Counts.of(jdbc)).isEqualTo(new Counts(8, 8, 1, 0));
            Map<String, Object> campaign = jdbc.queryForMap(
                "SELECT id, name, status, total_quota, issued_count, benefit_type, benefit_value FROM coupon_campaign");
            firstCampaign = ((Number) campaign.get("id")).longValue();
            firstUsers = jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class);
            System.out.println("[coupon-fixture] start#1 campaign=" + campaign + " users=" + firstUsers);
            assertAll(
                () -> assertThat(campaign.get("name")).isEqualTo("로컬 쿠폰 캠페인"),
                () -> assertThat(campaign.get("status")).isEqualTo("ACTIVE"),
                () -> assertThat(((Number) campaign.get("total_quota")).longValue()).isEqualTo(5L),
                () -> assertThat(((Number) campaign.get("issued_count")).longValue()).isZero(),
                () -> assertThat(campaign.get("benefit_type")).isEqualTo("FIXED_AMOUNT"),
                () -> assertThat(((Number) campaign.get("benefit_value")).longValue()).isEqualTo(5_000L),
                () -> assertThat(jdbc.queryForList("SELECT balance FROM point", Long.class)).hasSize(8).containsOnly(0L),
                () -> assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM point", Long.class)).isEqualTo(8L),
                () -> assertThat(facade.status()).as("준비가 수용을 열지 않는다").isFalse()
            );

            RedisTemplate<String, String> master = master(context);
            CouponRedisTarget.verify(context.getBean(RedisProperties.class), master);
            assertThat(master.type(CouponAdmissionRedisRepository.PERMITS_KEY)).isEqualTo(DataType.NONE);
            facade.open();
            try {
                CouponIssueResult issued = facade.issue(firstUsers.get(0), firstCampaign);
                assertThat(issued.created()).isTrue();
            } finally {
                facade.close();
            }
            assertThat(master.type(CouponAdmissionRedisRepository.PERMITS_KEY)).as("정상 반환으로 키가 비었다").isEqualTo(DataType.NONE);
            issuedCoupon = jdbc.queryForMap("SELECT id, user_id, campaign_id, benefit_type, benefit_value, created_at FROM coupon");
        }

        // 4. 같은 DB 를 보존한 재시작(true + update): 새 데이터만 추가, 기존 효과 불변. 이어서 같은 컨텍스트의 Runner 재호출을 따로 본다.
        try (ConfigurableApplicationContext context = boot("--spring.jpa.hibernate.ddl-auto=update", "--commerce.coupon.fixture.enabled=true")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            CouponFacade facade = context.getBean(CouponFacade.class);
            Counts afterRestart = Counts.of(jdbc);
            Map<String, Object> oldCampaign = jdbc.queryForMap(
                "SELECT name, status, total_quota, issued_count, benefit_type, benefit_value FROM coupon_campaign WHERE id = ?", firstCampaign);
            List<Long> campaigns = jdbc.queryForList("SELECT id FROM coupon_campaign WHERE name = '로컬 쿠폰 캠페인' ORDER BY id", Long.class);
            Long newCampaign = campaigns.get(campaigns.size() - 1);
            System.out.println("[coupon-fixture] restart counts=" + afterRestart + " campaigns=" + campaigns + " oldCampaign=" + oldCampaign);
            assertAll(
                () -> assertThat(afterRestart).isEqualTo(new Counts(16, 16, 2, 1)),
                () -> assertThat(campaigns).hasSize(2).startsWith(firstCampaign),
                () -> assertThat(((Number) oldCampaign.get("issued_count")).longValue()).as("기존 발급 수 보존").isEqualTo(1L),
                () -> assertThat(oldCampaign.get("status")).isEqualTo("ACTIVE"),
                () -> assertThat(((Number) oldCampaign.get("benefit_value")).longValue()).isEqualTo(5_000L),
                () -> assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Long.class, newCampaign)).isZero(),
                () -> assertThat(jdbc.queryForMap("SELECT id, user_id, campaign_id, benefit_type, benefit_value, created_at FROM coupon"))
                    .as("기존 쿠폰 행 불변").isEqualTo(issuedCoupon),
                () -> assertThat(jdbc.queryForList("SELECT id FROM users WHERE id <= ? ORDER BY id", Long.class, firstUsers.get(7)))
                    .as("기존 사용자 보존").isEqualTo(firstUsers),
                () -> assertThat(jdbc.queryForList("SELECT balance FROM point", Long.class)).containsOnly(0L),
                () -> assertThat(facade.status()).isFalse()
            );

            // 기존 DB ID 로 발급 API 경로를 그대로 쓸 수 있다.
            CouponRedisTarget.verify(context.getBean(RedisProperties.class), master(context));
            facade.open();
            try {
                assertThat(facade.issue(firstUsers.get(1), firstCampaign).created()).isTrue();
            } finally {
                facade.close();
            }
            assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Long.class, firstCampaign)).isEqualTo(2L);

            // 같은 컨텍스트에서 Runner 를 다시 호출하면 그 호출도 새 데이터만 추가한다(재시작 근거와 별개).
            ApplicationArguments noArgs = new DefaultApplicationArguments();
            context.getBean(RUNNER, ApplicationRunner.class).run(noArgs);
            Counts afterRepeat = Counts.of(jdbc);
            System.out.println("[coupon-fixture] repeat-runner counts=" + afterRepeat);
            assertAll(
                () -> assertThat(afterRepeat).isEqualTo(new Counts(24, 24, 3, 2)),
                () -> assertThat(jdbc.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?", Long.class, firstCampaign)).isEqualTo(2L)
            );
            assertThat(master(context).type(CouponAdmissionRedisRepository.PERMITS_KEY)).isEqualTo(DataType.NONE);
        }
    }

    @DisplayName("준비 TX 중간(사용자·Point 저장 뒤 캠페인 저장)에서 실패하면 기동이 실패하고, rollback 으로 이번 준비 데이터가 남지 않는다.")
    @Test
    void midPreparationFailureRollsBackAndStopsStartup() {
        try (ConfigurableApplicationContext context = boot("--spring.jpa.hibernate.ddl-auto=create")) {
            assertThat(Counts.of(context.getBean(JdbcTemplate.class))).isEqualTo(new Counts(0, 0, 0, 0));
        }

        IllegalStateException injected = new IllegalStateException("캠페인 저장 실패 주입");
        AtomicReference<Counts> seenInsideTransaction = new AtomicReference<>();
        ApplicationContextInitializer<ConfigurableApplicationContext> failCampaignSave = context ->
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof CouponCampaignJpaRepository)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), bean.getClass().getInterfaces(),
                        (proxy, method, args) -> {
                            if (method.getName().equals("save")) {
                                // 같은 TX 연결에서 본 행 수: 실패 직전 사용자·Point 가 실제로 INSERT 됐음을 남긴다.
                                seenInsideTransaction.set(Counts.of(context.getBean(JdbcTemplate.class)));
                                throw injected;
                            }
                            try {
                                return method.invoke(bean, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
                }
            });

        assertThatThrownBy(() -> boot(List.of(failCampaignSave),
            "--spring.jpa.hibernate.ddl-auto=update", "--commerce.coupon.fixture.enabled=true").close())
            .satisfies(e -> assertThat(causeChain(e)).as("준비 예외가 기동 실패로 전달된다").contains(injected));

        try (ConfigurableApplicationContext context = boot("--spring.jpa.hibernate.ddl-auto=update")) {
            Counts afterFailure = Counts.of(context.getBean(JdbcTemplate.class));
            System.out.println("[coupon-fixture] failed-start insideTx=" + seenInsideTransaction.get() + " afterRestart=" + afterFailure);
            assertAll(
                () -> assertThat(seenInsideTransaction.get()).as("실패 직전 TX 안에서 본 행").isEqualTo(new Counts(8, 8, 0, 0)),
                () -> assertThat(afterFailure).as("rollback 으로 이번 준비 데이터가 남지 않는다").isEqualTo(new Counts(0, 0, 0, 0))
            );
        }
    }
}
