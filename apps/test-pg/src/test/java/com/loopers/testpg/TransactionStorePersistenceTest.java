package com.loopers.testpg;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("테스트 PG 거래 저장")
class TransactionStorePersistenceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("생성한 거래와 멱등 응답은 저장소를 다시 열어도 유지된다")
    void keepsCreatedTransactionAndIdempotentResponseAfterRestart() {
        Path dataFile = temporaryDirectory.resolve("transactions.json");
        ObjectMapper objectMapper = new ObjectMapper();
        TransactionStore firstRun = new TransactionStore(dataFile, objectMapper);

        TransactionStore.Submission first = firstRun.confirm(
            "attempt-persisted", "test-payment-persisted", "LP-ORD-PERSISTED-0001", 7_000);
        TransactionStore.Transaction created = first.transaction();

        TransactionStore restarted = new TransactionStore(dataFile, objectMapper);
        TransactionStore.Submission repeated = restarted.confirm(
            "attempt-persisted", "test-payment-persisted", "LP-ORD-PERSISTED-0001", 7_000);

        assertThat(restarted.findByTransactionKey(created.transactionKey())).contains(created);
        assertThat(repeated).isEqualTo(first);
    }
}
