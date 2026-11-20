package com.loopers.application.ranking;

import com.loopers.domain.ranking.PublishedRankingRepository;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.LocalDate;

@Service
public class WeeklyRankingQueryService {
    private final PublishedRankingRepository rankings;
    private final WeeklyRankingReader reader;

    public WeeklyRankingQueryService(PublishedRankingRepository rankings, WeeklyRankingReader reader) {
        this.rankings = rankings;
        this.reader = reader;
    }

    public WeeklyRankingInfo query(LocalDate periodStart, int limit) {
        try {
            var snapshot = rankings.findPublished(periodStart).orElseThrow(() ->
                new WeeklyRankingQueryException(WeeklyRankingQueryException.Reason.NOT_PUBLISHED));
            return reader.read(snapshot, limit);
        } catch (RuntimeException exception) {
            if (unavailable(exception)) {
                throw new WeeklyRankingQueryException(WeeklyRankingQueryException.Reason.UNAVAILABLE, exception);
            }
            throw exception;
        }
    }

    private boolean unavailable(Throwable failure) {
        for (int depth = 0; failure != null && depth < 32; depth++, failure = failure.getCause()) {
            if (failure instanceof DataAccessResourceFailureException || failure instanceof QueryTimeoutException
                || failure instanceof CannotCreateTransactionException || failure instanceof TransactionTimedOutException
                || failure instanceof SQLTimeoutException || failure instanceof jakarta.persistence.QueryTimeoutException
                || (failure instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08"))) {
                return true;
            }
        }
        return false;
    }
}
