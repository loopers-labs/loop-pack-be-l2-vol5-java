package com.loopers.support.transaction;

import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.product.ProductCacheTestSupport;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("AfterCommitRunner 는 실제 commit 뒤에만 작업을 동기 실행하고, 작업 예외는 로그로만 남긴다.")
@ExtendWith(OutputCaptureExtension.class)
class AfterCommitRunnerIntegrationTest extends ProductCacheTestSupport {

    @Autowired
    private AfterCommitRunner afterCommitRunner;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private BrandJpaRepository brandJpaRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long committedBrandCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM brand", Long.class);
    }

    @DisplayName("commit 이 끝난 뒤 같은 스레드에서 실행되며, 실행 시점에 다른 연결에서도 commit 결과가 보인다.")
    @Test
    void runsAfterCommitOnSameThread() {
        List<Object> observed = new ArrayList<>();
        Thread caller = Thread.currentThread();

        transactionTemplate.executeWithoutResult(status -> {
            brandJpaRepository.save(BrandModel.create("나이키"));
            afterCommitRunner.runAfterCommit("검증", () -> {
                observed.add(Thread.currentThread() == caller);
                observed.add(committedBrandCount());
            });
            observed.add("registered");
        });

        assertThat(observed).containsExactly("registered", true, 1L);
    }

    @DisplayName("rollback 되면 작업을 실행하지 않는다.")
    @Test
    void skipsOnRollback() {
        List<String> executed = new ArrayList<>();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            brandJpaRepository.save(BrandModel.create("나이키"));
            afterCommitRunner.runAfterCommit("검증", () -> executed.add("run"));
            throw new IllegalStateException("등록 뒤 주입한 실패");
        })).hasMessage("등록 뒤 주입한 실패");

        assertAll(
            () -> assertThat(executed).isEmpty(),
            () -> assertThat(committedBrandCount()).isZero()
        );
    }

    @DisplayName("작업 예외는 호출자에게 전파하지 않고 로그를 남기며, 다음 작업은 계속 실행한다.")
    @Test
    void logsTaskFailure(CapturedOutput output) {
        List<String> executed = new ArrayList<>();

        transactionTemplate.executeWithoutResult(status -> {
            brandJpaRepository.save(BrandModel.create("나이키"));
            afterCommitRunner.runAfterCommit("실패 작업", () -> {
                throw new IllegalStateException("주입한 작업 실패");
            });
            afterCommitRunner.runAfterCommit("다음 작업", () -> executed.add("next"));
        });

        assertAll(
            () -> assertThat(committedBrandCount()).isEqualTo(1L),
            () -> assertThat(executed).containsExactly("next"),
            () -> assertThat(output).contains("[operation = 실패 작업]").contains("주입한 작업 실패")
        );
    }

    @DisplayName("실제 트랜잭션 밖에서 등록하면 commit 시점을 정할 수 없으므로 IllegalStateException 으로 거절한다.")
    @Test
    void rejectsOutsideTransaction() {
        assertThatThrownBy(() -> afterCommitRunner.runAfterCommit("검증", () -> { }))
            .isInstanceOf(IllegalStateException.class);
    }
}
