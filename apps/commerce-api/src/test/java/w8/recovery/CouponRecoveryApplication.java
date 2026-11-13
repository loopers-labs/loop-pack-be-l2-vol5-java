package w8.recovery;

import com.loopers.application.coupon.CouponIssuanceService;
import com.loopers.application.coupon.CouponReservationInfo;
import com.loopers.application.user.UserResolver;
import com.loopers.config.AdminBoundaryConfig;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.user.UserIdentity;
import com.loopers.domain.user.UserIdentityRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.UserRepositoryImpl;
import com.loopers.interfaces.api.commerce.CommerceErrors;
import com.loopers.interfaces.api.commerce.CommerceWebConfiguration;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.NameMatchMethodPointcutAdvisor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs the production coupon HTTP and worker paths against the parent test's existing stores. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = KafkaAutoConfiguration.class)
@EntityScan({"com.loopers.domain.coupon", "com.loopers.domain.user"})
@EnableJpaRepositories("com.loopers.infrastructure.user")
@ComponentScan({"com.loopers.interfaces.api.coupon", "com.loopers.application.coupon",
    "com.loopers.infrastructure.coupon", "com.loopers.config.coupon", "com.loopers.config.jackson"})
@Import({RedisConfig.class, UserResolver.class, UserRepositoryImpl.class, AdminBoundaryConfig.class,
    CommerceErrors.class, CommerceWebConfiguration.class})
public class CouponRecoveryApplication implements UserIdentityRepository {
    public static void main(String[] args) throws Exception {
        var context = (WebServerApplicationContext) SpringApplication.run(CouponRecoveryApplication.class, args);
        Path ready = Path.of("ready.tmp");
        Files.writeString(ready, Integer.toString(context.getWebServer().getPort()));
        Files.move(ready, Path.of("ready"), StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public Optional<UserIdentity> findByExternalId(String id) {
        return "applicant-4".equals(id) ? Optional.of(new UserIdentity(4L, UserRole.CUSTOMER)) : Optional.empty();
    }

    @Override
    public Optional<String> findExternalId(long userId) {
        return userId == 4L ? Optional.of("applicant-4") : Optional.empty();
    }

    @Bean
    ApplicationRunner observeWorker(CouponIssuanceService service, CouponRepository coupons) {
        return args -> {
            var proxy = (Advised) service;
            Path polled = Path.of("polled");
            advise(proxy, "findWaiting", invocation -> {
                Object result = invocation.proceed();
                if (result instanceof List<?> batch && !batch.isEmpty() && batch.getFirst() instanceof CouponReservationInfo waiting) {
                    Path seen = Path.of("first-waiting-" + waiting.reservationId());
                    if (Files.notExists(seen)) {
                        Files.createFile(seen);
                    }
                }
                if (Files.notExists(polled)) {
                    Files.createFile(polled);
                }
                return result;
            });
            if (args.containsOption("hold-issuance")) {
                advise(proxy, "issue", invocation -> {
                    pauseIssuance();
                    return null;
                });
            }
            if (args.containsOption("hold-issuance-sql")) {
                // saveCoupon persists an IDENTITY row immediately. Its issuing TX still owns the campaign lock.
                advise((Advised) coupons, "saveCoupon", invocation -> {
                    Object result = invocation.proceed();
                    pauseIssuance();
                    return result;
                });
            }
        };
    }

    private static void pauseIssuance() throws Exception {
        Files.createFile(Path.of("issuance-paused"));
        new CountDownLatch(1).await(90, TimeUnit.SECONDS);
        throw new IllegalStateException("Test must terminate the original worker before the issuance deadline");
    }

    private static void advise(Advised proxy, String method, MethodInterceptor advice) {
        var advisor = new NameMatchMethodPointcutAdvisor(advice);
        advisor.setMappedName(method);
        proxy.addAdvisor(0, advisor);
    }
}
