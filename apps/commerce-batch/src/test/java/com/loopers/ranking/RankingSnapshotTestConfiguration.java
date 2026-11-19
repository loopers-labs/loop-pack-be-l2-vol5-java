package com.loopers.ranking;

import com.loopers.domain.ranking.model.vo.InputFingerprint;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.domain.ranking.model.RankingSnapshot;
import com.loopers.domain.ranking.repository.RankingSnapshotRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@TestConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan({"com.loopers.config.jpa", "com.loopers.application.ranking", "com.loopers.infrastructure.persistence.ranking"})
class RankingSnapshotTestConfiguration {
    @Bean
    Control control() {
        return new Control();
    }

    @Bean
    @Primary
    RankingSnapshotRepository observedSnapshotRepository(
        @Qualifier("jdbcRankingSnapshotRepository") RankingSnapshotRepository delegate, DataSource dataSource, Control control) {
        return new ObservedRepository(delegate, new JdbcTemplate(dataSource), control);
    }

    static final class Control {
        final AtomicInteger reserves = new AtomicInteger();
        final AtomicInteger copies = new AtomicInteger();
        volatile Runnable beforeReserve = () -> {};
        volatile Runnable afterReserve = () -> {};
        volatile Runnable afterCopy = () -> {};
        volatile Runnable beforeComplete = () -> {};
        volatile Runnable afterComplete = () -> {};
        volatile String isolation;
        volatile long rowsBeforeFailure;

        void reset() {
            reserves.set(0);
            copies.set(0);
            beforeReserve = () -> {};
            afterReserve = () -> {};
            afterCopy = () -> {};
            beforeComplete = () -> {};
            afterComplete = () -> {};
            isolation = null;
            rowsBeforeFailure = 0;
        }
    }

    private record ObservedRepository(RankingSnapshotRepository delegate, JdbcTemplate jdbc, Control control)
        implements RankingSnapshotRepository {
        @Override
        public Optional<RankingSnapshot> findById(UUID id) {
            return delegate.findById(id);
        }

        @Override
        public void reserve(UUID id, RankingPeriod period, int revision) {
            control.reserves.incrementAndGet();
            control.beforeReserve.run();
            delegate.reserve(id, period, revision);
            control.afterReserve.run();
        }

        @Override
        public void copyInputs(UUID id, RankingPeriod period) {
            control.copies.incrementAndGet();
            delegate.copyInputs(id, period);
            control.isolation = jdbc.queryForObject("SELECT @@transaction_isolation", String.class);
            control.afterCopy.run();
        }

        @Override
        public InputFingerprint fingerprint(UUID id, RankingPeriod period) {
            return delegate.fingerprint(id, period);
        }

        @Override
        public RankingSnapshot complete(UUID id, InputFingerprint inputs) {
            control.beforeComplete.run();
            var result = delegate.complete(id, inputs);
            control.afterComplete.run();
            return result;
        }
    }
}
