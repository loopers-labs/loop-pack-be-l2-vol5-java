package com.loopers.domains.order.out;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRelaySettingsJpaRepository extends JpaRepository<OutboxRelaySettingsJpaEntity, Long> {
}
