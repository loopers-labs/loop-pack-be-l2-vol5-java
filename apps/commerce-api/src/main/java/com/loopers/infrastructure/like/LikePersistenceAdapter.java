package com.loopers.infrastructure.like;

import com.loopers.application.like.port.LikeRepository;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.ProductId;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;

@Repository
public class LikePersistenceAdapter implements LikeRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public LikePersistenceAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean save(Like like) {
        // on duplicate key update는 드라이버 기본 설정(found rows)에서 중복이어도 영향 행 수 1을 돌려준다.
        // INSERT IGNORE는 유일 키 중복이면 0을 돌려주므로 영향 행 수로 새 관계 여부를 판단할 수 있다.
        return jdbc.update("insert ignore into product_likes(user_id, product_id) values (:u,:p)",
            Map.of("u", like.userId(), "p", like.productId().value())) > 0;
    }

    @Override
    public boolean delete(Like like) {
        return jdbc.update("delete from product_likes where user_id=:u and product_id=:p",
            Map.of("u", like.userId(), "p", like.productId().value())) > 0;
    }

    @Override
    public List<ProductId> findActiveProductIds(long userId, int page, int size) {
        return jdbc.query("select l.product_id from product_likes l join products p on p.id=l.product_id "
                + "where l.user_id=:u and p.deleted=false order by l.product_id desc limit :size offset :offset",
            Map.of("u", userId, "size", size, "offset", (long) page * size), (rs, row) -> new ProductId(rs.getLong(1)));
    }
}
