package com.navan.inventory.common;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class NavanFeeKindConverter implements AttributeConverter<NavanFeeKind, String> {

    @Override
    public String convertToDatabaseColumn(NavanFeeKind attribute) {
        return attribute == null ? null : attribute.wire();
    }

    @Override
    public NavanFeeKind convertToEntityAttribute(String dbData) {
        return dbData == null ? null : NavanFeeKind.fromWire(dbData);
    }
}
