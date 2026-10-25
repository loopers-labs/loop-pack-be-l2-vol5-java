package com.loopers.testpg.domain;

public interface LookupScenarioRepository {
    LookupScenario find();

    LookupScenario nextResponse();

    void save(LookupScenario scenario);
}
