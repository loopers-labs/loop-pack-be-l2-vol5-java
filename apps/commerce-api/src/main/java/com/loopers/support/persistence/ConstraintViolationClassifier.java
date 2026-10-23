package com.loopers.support.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 저장 예외의 원인 사슬에서 이름 붙인 unique 제약만 식별한다(설계 2.7). 식별하지 못한 제약 위반·기술 오류는 빈 값이며,
 * 호출자는 그 경우 업무 충돌로 바꾸지 않고 500 으로 둔다. 예외 메시지·SQL 원문을 결과에 담지 않는다.
 */
@Component
public class ConstraintViolationClassifier {

    private static final Set<String> NAMED_UNIQUE = Set.of(
        "uk_payment_order", "uk_payment_key", "uk_payment_transaction", "uk_payment_conflict_payment");

    /** Hibernate 가 추출한 제약 이름의 테이블 접두어(MySQL 의 "payment.uk_payment_key")를 떼고 비교한다. */
    public Optional<String> namedUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                String name = violation.getConstraintName();
                String bare = name.substring(name.lastIndexOf('.') + 1);
                return NAMED_UNIQUE.contains(bare) ? Optional.of(bare) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
