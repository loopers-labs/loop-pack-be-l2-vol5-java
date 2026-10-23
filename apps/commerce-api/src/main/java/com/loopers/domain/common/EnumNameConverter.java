package com.loopers.domain.common;

import jakarta.persistence.AttributeConverter;

/**
 * 신규 enum 열을 이름 문자열 VARCHAR 로 저장한다(설계 3.6). @Enumerated 매핑은 Hibernate 6.6 이 값 CHECK 를 자동으로 붙여
 * 설계 표(CHECK 는 amount > 0 만)와 보존 전환 DB 와 달라지므로 변환기를 쓴다. 허용값 결합은 도메인 guard 가 보호한다.
 */
public abstract class EnumNameConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected EnumNameConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E value) {
        return value == null ? null : value.name();
    }

    /** 알 수 없는 값은 조용히 바꾸지 않고 읽기 실패로 드러낸다. */
    @Override
    public E convertToEntityAttribute(String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
