package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.OutboxRelaySettings;
import com.loopers.domains.order.app.port.RelaySettings;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRelaySettingsPersistenceAdapter implements OutboxRelaySettings {
    private static final long SETTINGS_ID = 1L;

    private final OutboxRelaySettingsJpaRepository outboxRelaySettingsJpaRepository;

    public OutboxRelaySettingsPersistenceAdapter(OutboxRelaySettingsJpaRepository outboxRelaySettingsJpaRepository) {
        this.outboxRelaySettingsJpaRepository = outboxRelaySettingsJpaRepository;
    }

    // relay가 회차마다 읽는다. 범위 검증은 app의 RelaySettings.sanitize가 하므로 여기서는 저장된 값을 그대로 옮긴다.
    @Override
    @Transactional(readOnly = true)
    public Optional<RelaySettings> current() {
        return outboxRelaySettingsJpaRepository.findById(SETTINGS_ID)
            .map(entity -> new RelaySettings(entity.batchSize(), Duration.ofMillis(entity.maxRoundMillis()), entity.enabled()));
    }
}
