package com.loopers.infrastructure.point;

import com.loopers.domain.point.PointBalance;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class PointBalanceConverter implements AttributeConverter<PointBalance, Long> {

    @Override
    public Long convertToDatabaseColumn(PointBalance attribute) {
        return attribute == null ? null : attribute.amount();
    }

    @Override
    public PointBalance convertToEntityAttribute(Long databaseValue) {
        return databaseValue == null ? null : new PointBalance(databaseValue);
    }
}
