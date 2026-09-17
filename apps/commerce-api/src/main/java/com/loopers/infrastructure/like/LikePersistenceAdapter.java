package com.loopers.infrastructure.like;

import com.loopers.application.like.port.LikeRepository;
import com.loopers.domain.like.Like;
import com.loopers.domain.product.ProductId;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class LikePersistenceAdapter implements LikeRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public LikePersistenceAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void save(Like like) {
        jdbc.update("insert into product_likes(user_id, product_id) values (:u,:p) on duplicate key update user_id=user_id",
            Map.of("u", like.userId(), "p", like.productId().value()));
    }

    @Override
    public void delete(Like like) {
        jdbc.update("delete from product_likes where user_id=:u and product_id=:p",
            Map.of("u", like.userId(), "p", like.productId().value()));
    }

    @Override
    public Map<ProductId, Long> countByProductIds(Collection<ProductId> ids) {
        Map<ProductId, Long> result = new HashMap<>();
        if (ids.isEmpty()) { return result; }
        jdbc.query("select product_id, count(*) as total from product_likes where product_id in (:ids) group by product_id",
            Map.of("ids", ids.stream().map(ProductId::value).toList()), (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                result.put(new ProductId(rs.getLong("product_id")), rs.getLong("total")));
        return result;
    }

    @Override
    public List<ProductId> findActiveProductIds(long userId, int page, int size) {
        return jdbc.query("select l.product_id from product_likes l join products p on p.id=l.product_id "
                + "where l.user_id=:u and p.deleted=false order by l.product_id desc limit :size offset :offset",
            Map.of("u", userId, "size", size, "offset", (long) page * size), (rs, row) -> new ProductId(rs.getLong(1)));
    }
}
