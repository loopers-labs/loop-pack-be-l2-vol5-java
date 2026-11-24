package com.loopers.infrastructure.ranking;

import com.loopers.application.ranking.WeeklyRankingQueryException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns its pool without contributing a second DataSource/JdbcTemplate bean to main JPA auto-configuration. */
public final class RankingReadResource implements AutoCloseable {
    private final HikariDataSource pool;
    private final JdbcTemplate jdbc;

    public RankingReadResource(boolean enabled, String url, String username, String password) {
        if (!enabled) {
            this.pool = null;
            this.jdbc = null;
            return;
        }
        if (url == null || !url.startsWith("jdbc:mysql:") || username == null || username.isBlank() || password == null) {
            throw new IllegalArgumentException("Enabled weekly ranking requires an explicit separate MySQL read configuration");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setReadOnly(true);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(500);
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", "1000");
        config.addDataSourceProperty("socketTimeout", "2000");
        this.pool = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(pool);
        this.jdbc.setQueryTimeout(2);
    }

    public JdbcTemplate jdbc() {
        if (jdbc == null) {
            throw new WeeklyRankingQueryException(WeeklyRankingQueryException.Reason.UNAVAILABLE);
        }
        return jdbc;
    }

    @Override
    public void close() {
        if (pool != null) {
            pool.close();
        }
    }
}
