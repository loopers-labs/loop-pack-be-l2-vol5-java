package com.loopers.batch.fixture;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 테스트에서 실행 기록을 "kill된 상태"로 만들고, 운영 절차의 정리 SQL(runbook/mark-execution-failed.sql)을 그대로 실행한다 (W9 D-7).
 */
public class StuckExecutionRecovery {

    private static final String RUNBOOK = "runbook/mark-execution-failed.sql";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    public StuckExecutionRecovery(DataSource dataSource, TransactionTemplate transactionTemplate) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * kill -9 흉내: 아무도 FAILED를 쓰지 못한 것처럼 job과 실패한 step을 STARTED로, END_TIME을 비워 둔다.
     */
    public void leaveAsStarted(long jobExecutionId) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", jobExecutionId);
        jdbc.update("UPDATE BATCH_JOB_EXECUTION SET STATUS = 'STARTED', EXIT_CODE = 'UNKNOWN', END_TIME = NULL WHERE JOB_EXECUTION_ID = :id", params);
        jdbc.update("UPDATE BATCH_STEP_EXECUTION SET STATUS = 'STARTED', EXIT_CODE = 'EXECUTING', END_TIME = NULL WHERE JOB_EXECUTION_ID = :id AND STATUS = 'FAILED'", params);
    }

    /**
     * 공개 commit 뒤 최종 기록 저장 실패 흉내 (D-7 실패 지도 #9): job과 마지막 step을 UNKNOWN으로 둔다.
     */
    public void leaveAsUnknown(long jobExecutionId, String stepName) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", jobExecutionId).addValue("step", stepName);
        jdbc.update("UPDATE BATCH_JOB_EXECUTION SET STATUS = 'UNKNOWN', EXIT_CODE = 'UNKNOWN' WHERE JOB_EXECUTION_ID = :id", params);
        jdbc.update("UPDATE BATCH_STEP_EXECUTION SET STATUS = 'UNKNOWN', EXIT_CODE = 'UNKNOWN' WHERE JOB_EXECUTION_ID = :id AND STEP_NAME = :step", params);
    }

    /**
     * 운영 절차의 정리 SQL을 한 TX로 실행한다.
     */
    public void markFailed(long jobExecutionId) {
        MapSqlParameterSource params = new MapSqlParameterSource("jobExecutionId", jobExecutionId);
        transactionTemplate.executeWithoutResult(status -> statements().forEach(sql -> jdbc.update(sql, params)));
    }

    private static List<String> statements() {
        try {
            String script = new ClassPathResource(RUNBOOK).getContentAsString(StandardCharsets.UTF_8);
            String withoutComments = Arrays.stream(script.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .reduce("", (a, b) -> a + b + "\n");
            return Arrays.stream(withoutComments.split(";"))
                .map(String::trim)
                .filter(sql -> !sql.isEmpty())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
