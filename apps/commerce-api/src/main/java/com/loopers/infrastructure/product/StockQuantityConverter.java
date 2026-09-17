package com.loopers.infrastructure.product;

import com.loopers.domain.product.StockQuantity;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class StockQuantityConverter implements AttributeConverter<StockQuantity, Long> {

    @Override
    public Long convertToDatabaseColumn(StockQuantity attribute) {
        return attribute == null ? null : attribute.amount();
    }

    @Override
    public StockQuantity convertToEntityAttribute(Long databaseValue) {
        return databaseValue == null ? null : new StockQuantity(databaseValue);
    }
}
