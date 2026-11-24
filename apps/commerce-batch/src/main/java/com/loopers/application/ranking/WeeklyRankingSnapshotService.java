package com.loopers.application.ranking;

import com.loopers.domain.ranking.FrozenRankingInput;
import com.loopers.domain.ranking.RankingPurchase;
import com.loopers.domain.ranking.RankingSnapshotStore;
import com.loopers.domain.ranking.WeeklyRankingKey;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;

@Service
public class WeeklyRankingSnapshotService {
    private final RankingSnapshotStore store;
    private final Clock clock;
    private final int pageSize;

    public WeeklyRankingSnapshotService(RankingSnapshotStore store,
                                       @Qualifier("weeklyRankingClock") Clock clock,
                                       @Value("${commerce.ranking.snapshot.page-size:500}") int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("Snapshot page size must be positive");
        }
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        this.pageSize = pageSize;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public FrozenRankingInput freeze(WeeklyRankingKey key) {
        Objects.requireNonNull(key, "Snapshot identity is required");
        // find() can establish the RR view. Capture the observation before that first SELECT.
        var observedAt = clock.instant();
        var existing = store.find(key);
        if (existing.isPresent()) {
            return existing.get();
        }

        // The initial, small-input implementation retains the complete immutable input in memory.
        var purchases = new ArrayList<RankingPurchase>();
        RankingPurchase after = null;
        while (true) {
            var page = store.readSourcePage(key, after, pageSize);
            purchases.addAll(page);
            if (page.size() < pageSize) {
                break;
            }
            after = page.getLast();
        }
        var frozen = new FrozenRankingInput(key, observedAt, purchases);
        store.insertManifest(frozen);
        for (int start = 0; start < frozen.purchases().size();) {
            int end = start + Math.min(pageSize, frozen.purchases().size() - start);
            store.insertInputs(key.snapshotId(), frozen.purchases().subList(start, end));
            start = end;
        }
        return frozen;
    }
}
