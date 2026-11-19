package com.loopers.ranking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcCursorItemReaderBuilder;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** C03 test-only wiring; no application Job or score model is introduced. */
final class PurchaseInputProbeJob {
    static final String READER_NAME = "purchaseProductProbe";
    static final String STEP_NAME = "probeProducts";

    enum ReaderKind { CURSOR, PAGING }
    enum FailurePoint { NONE, BEFORE_NINE, BEFORE_COMMIT }

    record Probe(Job job, TrackedReader reader, List<Long> processed, List<Long> written,
                 List<ProbeConnectionObservation.Event> failedTransaction) {}

    static Probe create(JobRepository repository, PlatformTransactionManager manager, DataSource dataSource,
                        JobParameters parameters, ReaderKind kind, FailurePoint failure,
                        ProbeConnectionObservation observation) throws Exception {
        var snapshot = Objects.requireNonNull(parameters.getString("snapshotId"));
        var reader = new TrackedReader(reader(dataSource, snapshot, kind));
        var jdbc = new JdbcTemplate(dataSource);
        var processed = new ArrayList<Long>();
        var written = new ArrayList<Long>();
        var failedTransaction = new ArrayList<ProbeConnectionObservation.Event>();
        var step = new StepBuilder(STEP_NAME, repository)
            .<Long, Long>chunk(2, manager)
            .reader(reader)
            .processor(id -> {
                processed.add(id);
                if (failure == FailurePoint.BEFORE_NINE && id == 9L) {
                    throw new IllegalStateException("BEFORE_NINE: injected processor failure");
                }
                return id;
            })
            .writer(items -> {
                int beginning = observation.events().size();
                for (Long id : items) {
                    jdbc.update("INSERT INTO batch_restart_probe (snapshot_id,product_id) VALUES (?,?)", snapshot, id);
                    written.add(id);
                }
                if (failure == FailurePoint.BEFORE_COMMIT && items.getItems().contains(9L)) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void beforeCommit(boolean readOnly) {
                            var events = observation.events();
                            failedTransaction.addAll(events.subList(beginning, events.size()));
                            throw new IllegalStateException("BEFORE_COMMIT: injected after writer and checkpoint SQL");
                        }
                    });
                }
            }).build();
        var job = new JobBuilder("purchaseInputProbe" + kind, repository).start(step).build();
        return new Probe(job, reader, processed, written, failedTransaction);
    }

    private static ItemStreamReader<Long> reader(DataSource source, String snapshot, ReaderKind kind) throws Exception {
        if (kind == ReaderKind.CURSOR) {
            var cursor = new JdbcCursorItemReaderBuilder<Long>()
                .name(READER_NAME).saveState(true).dataSource(source)
                .sql("SELECT DISTINCT product_id FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=? ORDER BY product_id ASC")
                .preparedStatementSetter(statement -> statement.setString(1, snapshot))
                .rowMapper((result, row) -> result.getLong("product_id"))
                .build();
            cursor.afterPropertiesSet();
            return cursor;
        }
        var paging = new JdbcPagingItemReaderBuilder<Long>()
            .name(READER_NAME).saveState(true).dataSource(source).pageSize(2)
            .selectClause("DISTINCT product_id").fromClause("weekly_ranking_snapshot_inputs")
            .whereClause("snapshot_id=:snapshotId").sortKeys(Map.of("product_id", Order.ASCENDING))
            .parameterValues(Map.of("snapshotId", snapshot))
            .rowMapper((result, row) -> result.getLong("product_id"))
            .build();
        // These delegates are manually created, so the container cannot invoke their lifecycle callbacks.
        paging.afterPropertiesSet();
        return paging;
    }

    static final class TrackedReader implements ItemStreamReader<Long> {
        private final ItemStreamReader<Long> delegate;
        private int opens;
        private int closes;

        private TrackedReader(ItemStreamReader<Long> delegate) {
            this.delegate = delegate;
        }

        int opens() {
            return opens;
        }

        int closes() {
            return closes;
        }

        @Override
        public void open(ExecutionContext context) {
            delegate.open(context);
            opens++;
        }

        @Override
        public Long read() throws Exception {
            return delegate.read();
        }

        @Override
        public void update(ExecutionContext context) {
            delegate.update(context);
        }

        @Override
        public void close() {
            delegate.close();
            closes++;
        }
    }
}
