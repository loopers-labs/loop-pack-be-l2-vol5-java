package com.loopers.support.transaction;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 현재 트랜잭션이 실제로 commit 된 뒤 실행할 작업을 등록한다.
 * 어떤 작업을 등록할지는 각 유스케이스가 정하고, 이 도구는 등록·commit 뒤 실행·작업 예외 로그만 담당한다.
 */
@Slf4j
@Component
public class AfterCommitRunner {

    /**
     * commit 뒤 같은 요청 스레드에서 작업을 동기 실행하고, rollback 이면 실행하지 않는다.
     * 작업의 예외는 이미 commit 된 결과를 실패 응답으로 바꾸지 않도록 잡아서 로그만 남기며 재시도하지 않는다.
     *
     * @throws IllegalStateException 실제 트랜잭션 밖에서 호출해 commit 시점을 정할 수 없을 때
     */
    public void runAfterCommit(String operation, Runnable task) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("commit 뒤 작업은 실제 트랜잭션 안에서만 등록할 수 있습니다. [operation = " + operation + "]");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("commit 뒤 작업이 실패했습니다. DB commit 결과는 유지하고 재시도하지 않습니다. [operation = {}]", operation, e);
                }
            }
        });
    }
}
