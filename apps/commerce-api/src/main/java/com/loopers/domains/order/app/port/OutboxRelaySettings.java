package com.loopers.domains.order.app.port;

import java.util.Optional;

public interface OutboxRelaySettings {
    // 설정 행을 그대로 돌려준다. 행이 없으면 비어 있다. 범위 검증은 RelaySettings.sanitize가 한다.
    Optional<RelaySettings> current();
}
