package com.loopers.domains.order.app.port;

import java.time.Duration;

public interface ReconcilePacer {
    void pause(Duration interval);
}
