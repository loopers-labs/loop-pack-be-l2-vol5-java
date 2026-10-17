package com.loopers.domain.like;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;

/**
 * 좋아요의 복합 식별자. 상품과 사용자의 조합이 곧 유일성이다.
 *
 * <p>JPA 복합키 규격에 따라 {@link Serializable}이며, record 라 동등성({@code equals}/{@code hashCode})이 값으로 정의된다.
 *
 * <p>기본키 컬럼 순서는 이 record 의 컴포넌트 선언 순서 {@code (product_id, user_id)} 이다. {@code modules:jpa} 의
 * {@code hibernate.column_ordering_strategy: legacy} 설정 덕분이며, 기본 전략은 컬럼을 물리 크기, 컬럼 이름 순으로 재배열해
 * 선언 순서를 무시한다. 일반 클래스로 바꾸면 legacy 에서도 속성 이름 순으로 정렬되므로 복합키는 record 로 선언한다.
 * 상품별 좋아요 수 집계({@code product_id} 조건)가 기본키의 왼쪽 접두로 인덱스를 타려면 이 순서가 필요하고,
 * 스키마 테스트({@code LikeRepositoryTest.Schema})가 결과 DDL 로 확인한다.
 */
@Embeddable
public record LikeId(
    @Column(name = "product_id", nullable = false) Long productId,
    @Column(name = "user_id", nullable = false) Long userId
) implements Serializable {

    public LikeId {
        if (userId == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "사용자는 필수입니다.");
        }
        if (productId == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "상품은 필수입니다.");
        }
    }
}
