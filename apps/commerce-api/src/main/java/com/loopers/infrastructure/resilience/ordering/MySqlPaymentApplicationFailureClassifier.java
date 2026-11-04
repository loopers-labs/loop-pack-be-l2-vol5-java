package com.loopers.infrastructure.resilience.ordering;

import com.loopers.application.ordering.gateway.PaymentApplicationFailureClassifier;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MySqlPaymentApplicationFailureClassifier implements PaymentApplicationFailureClassifier {
    @Override
    public boolean isDeadlock(Throwable failure) {
        if (failure == null) { return false; }
        var pending = new ArrayDeque<Throwable>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) { continue; }
            if (current instanceof SQLException sql) {
                if (sql.getErrorCode() == 1213 && "40001".equals(sql.getSQLState())) { return true; }
                if (sql.getNextException() != null) { pending.add(sql.getNextException()); }
            }
            if (current.getCause() != null) { pending.add(current.getCause()); }
        }
        return false;
    }
}
