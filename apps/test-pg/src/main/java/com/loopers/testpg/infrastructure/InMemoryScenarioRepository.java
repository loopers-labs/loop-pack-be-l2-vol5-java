package com.loopers.testpg.infrastructure;

import com.loopers.testpg.domain.ScenarioRepository;
import com.loopers.testpg.domain.ScenarioSetting;
import org.springframework.stereotype.Repository;

import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryScenarioRepository implements ScenarioRepository {
    private final ConcurrentHashMap<String, ScenarioSetting> settings = new ConcurrentHashMap<>();

    @Override
    public void register(String orderId, ScenarioSetting setting) {
        settings.put(orderId, setting);
    }

    @Override
    public ScenarioSetting find(String orderId) {
        return settings.getOrDefault(orderId, ScenarioSetting.defaultSetting());
    }
}
