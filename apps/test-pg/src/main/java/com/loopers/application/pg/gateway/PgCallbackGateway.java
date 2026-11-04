package com.loopers.application.pg.gateway;

import com.loopers.domain.pg.model.PgTransaction;

public interface PgCallbackGateway {
    void publish(PgTransaction completed);
}
