package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingKey;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 순위 Step의 Reader. 계산 중인 회차의 고정 점수를 점수 내림차순, 상품 ID 오름차순으로 페이지 단위로 읽는다.
 * 두 정렬 키를 합치면 회차 안에서 유일해 페이지 경계에서도 순서가 흔들리지 않는다.
 * 위치를 저장하지 않으므로 다시 실행하면 첫 행부터 읽는다.
 */
public final class FrozenScoreReader {

    public static final String NAME = "frozenScoreReader";

    private FrozenScoreReader() {
    }

    public static JdbcPagingItemReader<FrozenScore> create(DataSource dataSource, WeeklyRankingKey key, int pageSize) {
        MySqlPagingQueryProvider query = new MySqlPagingQueryProvider();
        query.setSelectClause("s.product_id, s.score");
        query.setFromClause("""
            weekly_ranking_scores s
            JOIN weekly_ranking_revisions r ON r.period_start = s.period_start AND r.revision = s.revision""");
        query.setWhereClause("s.period_start = :periodStart AND s.revision = :revision AND r.status = 'CALCULATING'");
        // 다음 페이지 조건은 정렬 키 이름으로 결과 컬럼 값을 읽으므로, 정렬 키에는 테이블 별칭을 붙이지 않는다.
        Map<String, Order> sortKeys = new LinkedHashMap<>();
        sortKeys.put("score", Order.DESCENDING);
        sortKeys.put("product_id", Order.ASCENDING);
        query.setSortKeys(sortKeys);

        JdbcPagingItemReader<FrozenScore> reader = new JdbcPagingItemReaderBuilder<FrozenScore>()
            .name(NAME)
            .dataSource(dataSource)
            .queryProvider(query)
            .parameterValues(Map.of("periodStart", key.period().start(), "revision", key.revision()))
            .pageSize(pageSize)
            .saveState(false)
            .rowMapper((result, rowNumber) -> new FrozenScore(result.getLong("product_id"), result.getLong("score")))
            .build();
        // 빌더는 초기화를 하지 않는다. 빈으로 등록하지 않고 만들어 쓸 때도 바로 읽을 수 있게 여기서 초기화한다.
        try {
            reader.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("순위 Reader를 초기화하지 못했다. key=" + key, e);
        }
        return reader;
    }
}
