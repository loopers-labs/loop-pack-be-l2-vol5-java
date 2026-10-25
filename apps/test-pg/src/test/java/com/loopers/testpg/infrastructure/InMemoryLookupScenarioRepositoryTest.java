package com.loopers.testpg.infrastructure;

import com.loopers.testpg.domain.LookupScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryLookupScenarioRepositoryTest {
    @DisplayName("기본은 정상이며 새 지연 설정으로 바꿔도 이전 요청이 읽은 설정은 유지한다.")
    @Test
    void replacesSettingWithoutMutatingPreviousSnapshot() {
        InMemoryLookupScenarioRepository repository = new InMemoryLookupScenarioRepository();
        assertThat(repository.find().delayMillis()).isZero();
        repository.save(new LookupScenario(1500));
        LookupScenario previous = repository.find();

        repository.save(new LookupScenario(0));

        assertThat(previous.delayMillis()).isEqualTo(1500);
        assertThat(repository.find().delayMillis()).isZero();
    }

    @DisplayName("동시 조회가 지정한 실패 횟수를 한 번씩 소비하고 나머지는 정상으로 처리된다.")
    @Test
    void consumesOnlyConfiguredFailuresConcurrently() throws Exception {
        InMemoryLookupScenarioRepository repository = new InMemoryLookupScenarioRepository();
        repository.save(new LookupScenario(0, 7, 503));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<LookupScenario>>();
            for (int index = 0; index < 40; index++) {
                futures.add(executor.submit(repository::nextResponse));
            }
            int failures = 0;
            for (var future : futures) {
                if (future.get().failureCount() > 0) {
                    failures++;
                }
            }
            assertThat(failures).isEqualTo(7);
        }
        assertThat(repository.find().failureCount()).isZero();
        assertThat(repository.nextResponse().failureCount()).isZero();
    }
}
