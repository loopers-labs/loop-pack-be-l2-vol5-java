package com.loopers.experiment;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Hibernate 가 준비하는 SQL 을 그대로 모은다 (실험 전용). 요청당 SQL 수와 실행 계획에 넣을 실제 SQL 을 얻는 데 씀.
 * Hibernate 가 이름으로 만들어 쓰므로 상태는 정적으로 둔다. 실험은 한 스레드에서 차례로 요청한다.
 */
public class SqlCapture implements StatementInspector {

    private static final List<String> STATEMENTS = new ArrayList<>();

    @Override
    public String inspect(String sql) {
        synchronized (STATEMENTS) {
            STATEMENTS.add(sql);
        }
        return sql;
    }

    static void clear() {
        synchronized (STATEMENTS) {
            STATEMENTS.clear();
        }
    }

    static List<String> statements() {
        synchronized (STATEMENTS) {
            return List.copyOf(STATEMENTS);
        }
    }
}
