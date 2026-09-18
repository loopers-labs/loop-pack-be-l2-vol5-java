package com.loopers.infrastructure.product;

import com.loopers.application.product.query.ProductQuery;
import com.loopers.application.product.query.ProductView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryImpl implements ProductQuery {
    private final JdbcTemplate jdbc;
    private static final String FROM = " from products p join brands b on b.id=p.brand_id where p.deleted_at is null and b.deleted_at is null";
    private static final String SELECT = "select p.id,p.name,p.price,b.id brand_id,b.name brand_name,"
        + "(select count(*) from product_likes l where l.product_id=p.id) like_count";
    private static final RowMapper<ProductView> ROW = (rs, row) -> new ProductView(rs.getLong("id"), rs.getString("name"),
        rs.getLong("price"), new ProductView.BrandView(rs.getLong("brand_id"), rs.getString("brand_name")), rs.getLong("like_count"));

    @Override
    public Optional<ProductView> find(long id) {
        return jdbc.query(SELECT + FROM + " and p.id=?", ROW, id).stream().findFirst();
    }

    @Override
    public Page<ProductView> list(Long brandId, Pageable pageable, String sort) {
        String filter = FROM + (brandId == null ? "" : " and p.brand_id=?");
        List<Object> args = new ArrayList<>();
        if (brandId != null) { args.add(brandId); }
        long total = jdbc.queryForObject("select count(*)" + filter, Long.class, args.toArray());
        String order = switch (sort) {
            case "price_asc" -> "p.price asc,p.id desc";
            case "likes_desc" -> "like_count desc,p.id desc";
            default -> "p.created_at desc,p.id desc";
        };
        args.add(pageable.getPageSize());
        args.add(pageable.getOffset());
        List<ProductView> items = jdbc.query(SELECT + filter + " order by " + order + " limit ? offset ?", ROW, args.toArray());
        return new PageImpl<>(items, pageable, total);
    }
}
