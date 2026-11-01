package com.loopers.support.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * 저장 예외의 원인 사슬에서 완료 기록의 명명 unique 두 개만 식별한다(소비 설계 5절). 메시지 문자열은 보지 않고
 * Hibernate 가 추출한 제약 metadata 만 쓴다. 식별하지 못하면 빈 값이며 호출자는 원래 오류로 둔다.
 */
@Component
public class PurchaseActivityConstraintClassifier {

    private static final Set<String> COMPLETION_UNIQUE = Set.of("uk_poc_event", "uk_poc_order");

    /** MySQL 이 붙이는 테이블 접두어("processed_order_confirmation.uk_poc_event")를 떼고 비교한다. */
    public Optional<String> completionUniqueViolation(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                if (name == null || violation.getKind() != ConstraintViolationException.ConstraintKind.UNIQUE) {
                    return Optional.empty();
                }
                String bare = name.substring(name.lastIndexOf('.') + 1);
                return COMPLETION_UNIQUE.contains(bare) ? Optional.of(bare) : Optional.empty();
            }
        }
        return Optional.empty();
    }
}
