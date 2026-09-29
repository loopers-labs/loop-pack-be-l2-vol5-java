package com.loopers.domain.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Money를 bigint 컬럼 하나로 저장한다.
 * 한 엔티티에 Money가 여러 개(예: 충전액·남은 금액) 있어도 컬럼 이름을 필드마다 정할 수 있다.
 */
@Converter
public class MoneyConverter implements AttributeConverter<Money, Long> {

    @Override
    public Long convertToDatabaseColumn(Money money) {
        return money == null ? null : money.amount();
    }

    @Override
    public Money convertToEntityAttribute(Long amount) {
        return amount == null ? null : Money.of(amount);
    }
}
