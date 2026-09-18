package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.BrandName;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class BrandNameConverter implements AttributeConverter<BrandName, String> {

    @Override
    public String convertToDatabaseColumn(BrandName name) {
        return name == null ? null : name.value();
    }

    @Override
    public BrandName convertToEntityAttribute(String name) {
        return name == null ? null : new BrandName(name);
    }
}
