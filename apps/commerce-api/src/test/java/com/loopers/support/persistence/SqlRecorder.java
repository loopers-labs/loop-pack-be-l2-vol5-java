package com.loopers.support.persistence;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 테스트 전용: Hibernate가 DB로 보내는 SQL을 그대로 기록한다.
 * {@code hibernate.session_factory.statement_inspector}에 클래스 이름으로 등록하면 Hibernate가 직접 만든다.
 */
public class SqlRecorder implements StatementInspector {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();
    private static final Pattern FROM_TABLE = Pattern.compile("\\bfrom (\\w+)");

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql.toLowerCase(Locale.ROOT));
        return sql;
    }

    public static void clear() {
        STATEMENTS.clear();
    }

    /**
     * 기록된 SELECT … FOR UPDATE가 잠근 테이블을 보낸 순서대로 돌려준다.
     */
    public static List<String> lockedTables() {
        return STATEMENTS.stream()
            .filter(sql -> sql.startsWith("select") && sql.contains(" for update"))
            .map(SqlRecorder::fromTable)
            .toList();
    }

    private static String fromTable(String sql) {
        Matcher matcher = FROM_TABLE.matcher(sql);
        return matcher.find() ? matcher.group(1) : "";
    }
}
