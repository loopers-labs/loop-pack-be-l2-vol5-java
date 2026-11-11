package com.loopers.application.coupon;

import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 설계 §10.2 의 TX 관리자 대역. 실제 DB TX 를 열지 않고, 호출 순서대로 지정한 종료 방식을 연출한다.
 * 동기화를 켜서 afterCompletion 등록을 정상 수락하고, 등록 수를 기록해 테스트가 먼저 확인할 수 있게 한다.
 * 실제 드라이버·네트워크가 UNKNOWN 을 만드는 재현이 아니다.
 */
class ScriptedTransactionManager implements PlatformTransactionManager {

    enum Ending {
        /** 정상 commit/rollback 과 해당 통지. */
        NORMAL,
        /** getTransaction 에서 연결 획득 실패. 콜백에 들어가지 않는다. */
        BEGIN_FAILS,
        /** commit 이 UNKNOWN 을 통지하고 예외. */
        COMMIT_UNKNOWN,
        /** commit 이 통지 없이 예외. */
        COMMIT_NO_NOTICE,
        /** commit 은 COMMITTED 를 통지했지만 그 뒤 예외. */
        COMMITTED_THEN_THROWS,
        /** rollback 이 UNKNOWN 을 통지하고 예외. */
        ROLLBACK_UNKNOWN,
        /** 콜백 예외 뒤 종료 처리가 COMMITTED 를 통지. 결과 확보 없는 COMMITTED 분기를 만든다. */
        ROLLBACK_REPORTS_COMMITTED
    }

    private final Deque<Ending> script = new ArrayDeque<>();
    private final List<Integer> registeredSynchronizations = new ArrayList<>();
    private int begun;

    ScriptedTransactionManager then(Ending ending) {
        script.addLast(ending);
        return this;
    }

    int begun() {
        return begun;
    }

    /** 각 TX 에서 종료 직전에 확인한 afterCompletion 등록 수. */
    List<Integer> registeredSynchronizations() {
        return registeredSynchronizations;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        Ending ending = script.isEmpty() ? Ending.NORMAL : script.pollFirst();
        begun++;
        if (ending == Ending.BEGIN_FAILS) {
            throw new CannotCreateTransactionException("연결 획득 실패(대역)");
        }
        TransactionSynchronizationManager.initSynchronization();
        return new ScriptedStatus(ending);
    }

    @Override
    public void commit(TransactionStatus status) {
        Ending ending = ((ScriptedStatus) status).ending;
        List<TransactionSynchronization> synchronizations = finish();
        switch (ending) {
            case COMMIT_UNKNOWN -> {
                notify(synchronizations, TransactionSynchronization.STATUS_UNKNOWN);
                throw new TransactionSystemException("commit 결과 불명(대역)");
            }
            case COMMIT_NO_NOTICE -> throw new TransactionSystemException("commit 중 통지 없이 실패(대역)");
            case COMMITTED_THEN_THROWS -> {
                notify(synchronizations, TransactionSynchronization.STATUS_COMMITTED);
                throw new TransactionSystemException("commit 뒤 예외(대역)");
            }
            default -> notify(synchronizations, TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    @Override
    public void rollback(TransactionStatus status) {
        Ending ending = ((ScriptedStatus) status).ending;
        List<TransactionSynchronization> synchronizations = finish();
        switch (ending) {
            case ROLLBACK_UNKNOWN -> {
                notify(synchronizations, TransactionSynchronization.STATUS_UNKNOWN);
                throw new TransactionSystemException("rollback 결과 불명(대역)");
            }
            case ROLLBACK_REPORTS_COMMITTED -> {
                notify(synchronizations, TransactionSynchronization.STATUS_COMMITTED);
                throw new TransactionSystemException("종료 처리 뒤 예외(대역)");
            }
            default -> notify(synchronizations, TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private List<TransactionSynchronization> finish() {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        registeredSynchronizations.add(synchronizations.size());
        TransactionSynchronizationManager.clearSynchronization();
        return synchronizations;
    }

    private static void notify(List<TransactionSynchronization> synchronizations, int completion) {
        synchronizations.forEach(s -> s.afterCompletion(completion));
    }

    private static final class ScriptedStatus extends SimpleTransactionStatus {
        private final Ending ending;

        private ScriptedStatus(Ending ending) {
            super(true);
            this.ending = ending;
        }
    }
}
