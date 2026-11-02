package testsupport.purchaseactivity;

import com.loopers.purchaseactivity.config.PurchaseActivityKafkaConfiguration;
import com.loopers.purchaseactivity.interfaces.OrderConfirmedListener;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.event.ConsumerStoppedEvent;

import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Configuration
@Import({PurchaseActivityDatabaseTestConfiguration.class, PurchaseActivityKafkaConfiguration.class,
        OrderConfirmedListener.class})
public class PurchaseActivityKafkaTestConfiguration {
    @Bean
    public ConsumerStops consumerStops() {
        return new ConsumerStops();
    }

    public static class ConsumerStops {
        private final LinkedBlockingQueue<ConsumerStoppedEvent> events = new LinkedBlockingQueue<>();

        @EventListener
        public void stopped(ConsumerStoppedEvent event) {
            events.add(event);
        }

        public void clear() {
            events.clear();
        }

        public ConsumerStoppedEvent awaitStop(Duration timeout) throws InterruptedException {
            return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
