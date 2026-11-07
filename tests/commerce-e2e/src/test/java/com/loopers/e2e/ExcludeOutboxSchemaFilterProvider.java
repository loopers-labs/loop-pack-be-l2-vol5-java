package com.loopers.e2e;

import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.mapping.Table;
import org.hibernate.tool.schema.spi.SchemaFilter;
import org.hibernate.tool.schema.spi.SchemaFilterProvider;

/**
 * W6 스키마 재현: Hibernate 스키마 생성에서 W7 의 order_event_outbox 만 뺀다. W6 revision 에는 이 테이블이 없었다.
 * W6 context 를 닫은 뒤 docs/week7/order-event-outbox.sql 로 같은 DB 에 만든다 (운영 배포와 같은 순서).
 */
public class ExcludeOutboxSchemaFilterProvider implements SchemaFilterProvider {
    static final String OUTBOX_TABLE = "order_event_outbox";

    private static final SchemaFilter FILTER = new SchemaFilter() {
        @Override
        public boolean includeNamespace(Namespace namespace) {
            return true;
        }

        @Override
        public boolean includeTable(Table table) {
            return !OUTBOX_TABLE.equalsIgnoreCase(table.getName());
        }

        @Override
        public boolean includeSequence(Sequence sequence) {
            return true;
        }
    };

    @Override
    public SchemaFilter getCreateFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getDropFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getTruncatorFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getMigrateFilter() {
        return FILTER;
    }

    @Override
    public SchemaFilter getValidateFilter() {
        return FILTER;
    }
}
