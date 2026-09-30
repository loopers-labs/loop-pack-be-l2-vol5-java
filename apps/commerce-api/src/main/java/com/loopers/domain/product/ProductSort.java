package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

// 세 정렬 모두 동률 시 name ASC, id ASC로 끝까지 깨진다 — 페이지네이션이 안정적으로 동작하려면
// 정렬 기준이 유일한 값까지 이어져야 한다 (id는 PK라 항상 유일함이 보장됨).
// (docs/week2/design.md 8번 섹션 근처 "정렬·동률" 논의 참고)
public enum ProductSort {
    LATEST,
    PRICE_ASC,
    LIKES_DESC;

    /**
     * API 계약의 쿼리 값(latest/price_asc/likes_desc, 소문자)을 enum으로 변환한다.
     * enum 상수 이름은 Java 관례대로 대문자를 쓰므로, Spring 기본 바인더(Enum.valueOf)에 맡기지 않고
     * 여기서 직접 매핑해 잘못된 값을 400으로 통제한다.
     */
    public static ProductSort fromQueryValue(String value) {
        return switch (value) {
            case "latest" -> LATEST;
            case "price_asc" -> PRICE_ASC;
            case "likes_desc" -> LIKES_DESC;
            default -> throw new CoreException(ErrorType.BAD_REQUEST, "잘못된 정렬 값입니다: " + value);
        };
    }
}
