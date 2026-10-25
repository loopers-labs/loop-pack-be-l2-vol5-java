package com.loopers.testpg.application;

import com.loopers.testpg.domain.LookupScenario;
import com.loopers.testpg.domain.LookupScenarioRepository;

public class LookupScenarioService {
    private final LookupScenarioRepository repository;

    public LookupScenarioService(LookupScenarioRepository repository) {
        this.repository = repository;
    }

    public LookupScenario current() {
        return repository.find();
    }

    public LookupScenario nextResponse() {
        return repository.nextResponse();
    }

    public void configure(LookupScenario scenario) {
        repository.save(scenario);
    }
}
