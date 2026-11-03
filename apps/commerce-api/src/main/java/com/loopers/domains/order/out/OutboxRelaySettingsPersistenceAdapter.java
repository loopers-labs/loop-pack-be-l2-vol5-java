package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.OutboxRelaySettings;
import com.loopers.domains.order.app.port.RelaySettings;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRelaySettingsPersistenceAdapter implements OutboxRelaySettings {
    private final OutboxRelaySettingsJpaRepository outboxRelaySettingsJpaRepository;

    public OutboxRelaySettingsPersistenceAdapter(OutboxRelaySettingsJpaRepository outboxRelaySettingsJpaRepository) {
        this.outboxRelaySettingsJpaRepository = outboxRelaySettingsJpaRepository;
    }

    @Override
    public Optional<RelaySettings> current() {
        return Optional.empty();
    }
}
