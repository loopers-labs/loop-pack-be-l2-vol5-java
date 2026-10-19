package com.loopers.infrastructure.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlConstraintErrorsTest {
    @ParameterizedTest
    @ValueSource(strings = {"uk_brand_name", "brands.uk_brand_name", "loopers.brands.uk_brand_name"})
    void recognizesOnlyTheNamedDuplicateConstraint(String key) {
        SQLException sql = new SQLException("Duplicate entry 'Shared' for key '" + key + "'", "23000", 1062);
        Throwable failure = new DataIntegrityViolationException("write failed", sql);

        assertThat(MySqlConstraintErrors.isDuplicateKey(failure, "uk_brand_name")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"uk_other", "uk_brand_name_suffix", "prefix_uk_brand_name"})
    void doesNotConvertOtherUniqueConstraints(String key) {
        SQLException sql = new SQLException("Duplicate entry 'Shared' for key 'brands." + key + "'", "23000", 1062);

        assertThat(MySqlConstraintErrors.isDuplicateKey(sql, "uk_brand_name")).isFalse();
    }

    @Test
    void doesNotConvertOtherSqlOrNonSqlFailures() {
        String message = "for key 'brands.uk_brand_name'";
        assertThat(MySqlConstraintErrors.isDuplicateKey(new SQLException(message, "HY000", 1205), "uk_brand_name")).isFalse();
        assertThat(MySqlConstraintErrors.isDuplicateKey(new SQLException(message, "23000", 1048), "uk_brand_name")).isFalse();
        assertThat(MySqlConstraintErrors.isDuplicateKey(new SQLException(message, "HY000", 1062), "uk_brand_name")).isFalse();
        assertThat(MySqlConstraintErrors.isDuplicateKey(new SQLException(null, "23000", 1062), "uk_brand_name")).isFalse();
        assertThat(MySqlConstraintErrors.isDuplicateKey(new IllegalStateException(message), "uk_brand_name")).isFalse();
    }
}
