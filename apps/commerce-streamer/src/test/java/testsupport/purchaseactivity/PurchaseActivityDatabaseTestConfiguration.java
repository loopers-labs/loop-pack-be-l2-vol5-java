package testsupport.purchaseactivity;

import com.loopers.purchaseactivity.config.PurchaseActivityPersistenceConfiguration;
import com.loopers.testcontainers.MySqlTestContainersConfig;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@EnableAutoConfiguration
@ComponentScan(basePackages = {
        "com.loopers.purchaseactivity.application",
        "com.loopers.purchaseactivity.infrastructure",
        "com.loopers.config.jpa"
})
@Import({PurchaseActivityPersistenceConfiguration.class, MySqlTestContainersConfig.class})
public class PurchaseActivityDatabaseTestConfiguration {
}
