package com.loopers.batch.job.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class SortedRowsChecksum {

    private static final int ROW_BY_ROW = Integer.MIN_VALUE;

    private final JdbcTemplate jdbc;

    SortedRowsChecksum(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    String of(String sortedQuery, String snapshotId, Line line) {
        MessageDigest digest = newDigest();
        jdbc.query(
            connection -> {
                PreparedStatement statement = connection.prepareStatement(
                    sortedQuery, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY
                );
                statement.setFetchSize(ROW_BY_ROW);
                statement.setString(1, snapshotId);
                return statement;
            },
            (RowCallbackHandler) rs -> digest.update((line.of(rs) + "\n").getBytes(StandardCharsets.UTF_8))
        );
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    interface Line {
        String of(ResultSet rs) throws SQLException;
    }
}
