package com.loopers.testpg.infrastructure;

import com.loopers.testpg.domain.LookupScenario;
import com.loopers.testpg.domain.LookupScenarioRepository;
import org.springframework.stereotype.Repository;

import java.util.concurrent.atomic.AtomicReference;

@Repository
public class InMemoryLookupScenarioRepository implements LookupScenarioRepository {
    private final AtomicReference<LookupScenario> scenario = new AtomicReference<>(new LookupScenario(0));

    @Override
    public LookupScenario find() {
        return scenario.get();
    }

    @Override
    public LookupScenario nextResponse() {
        return scenario.getAndUpdate(LookupScenario::afterResponse);
    }

    @Override
    public void save(LookupScenario setting) {
        scenario.set(setting);
    }
}
