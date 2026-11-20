package com.loopers.batch.job.ranking.step;

import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 고정 입력의 서로 다른 상품 ID를 읽고, 재시작에 필요한 페이지 키와 페이지 크기를 저장한다.
 * 재시작할 때 페이지 크기가 저장값과 다르면 실패한다. 저장된 읽은 수가 새 페이지 크기의 배수가 아니면
 * 페이지 안에서 건너뛸 위치가 틀려 상품을 빠뜨리거나 두 번 읽을 수 있기 때문이다.
 */
public class SnapshotProductPagingReader extends JdbcPagingItemReader<Long> {

    public SnapshotProductPagingReader(String name, DataSource dataSource, String snapshotId, int pageSize) {
        var provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("DISTINCT product_id");
        provider.setFromClause("ranking_snapshot_input FORCE INDEX (idx_ranking_snapshot_input_product)");
        provider.setWhereClause("snapshot_id = :snapshotId");
        provider.setSortKeys(Map.of("product_id", Order.ASCENDING));

        setName(name);
        setDataSource(dataSource);
        setQueryProvider(provider);
        setParameterValues(Map.of("snapshotId", snapshotId));
        setPageSize(pageSize);
        setRowMapper((rs, n) -> rs.getLong("product_id"));
        setSaveState(true);
    }

    @Override
    public void open(ExecutionContext context) {
        String pageSizeKey = getExecutionContextKey("page.size");
        if (context.containsKey(pageSizeKey) && context.getInt(pageSizeKey) != getPageSize()) {
            throw new IllegalStateException("저장된 페이지 크기 " + context.getInt(pageSizeKey) + "와 현재 chunk 크기 "
                + getPageSize() + "가 다릅니다. 원래 chunk 크기로 같은 parameter를 다시 실행하세요.");
        }
        super.open(context);
    }

    @Override
    public void update(ExecutionContext context) {
        super.update(context);
        context.putInt(getExecutionContextKey("page.size"), getPageSize());
    }
}
