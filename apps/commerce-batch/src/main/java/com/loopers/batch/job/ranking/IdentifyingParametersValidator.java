package com.loopers.batch.job.ranking;

import static java.util.stream.Collectors.toMap;

import java.util.Map;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.JobParametersValidator;

class IdentifyingParametersValidator implements JobParametersValidator {

    private final Map<String, Class<?>> signature;

    IdentifyingParametersValidator(Map<String, Class<?>> signature) {
        this.signature = Map.copyOf(signature);
    }

    @Override
    public void validate(JobParameters parameters) throws JobParametersInvalidException {
        JobParameters given = parameters == null ? new JobParameters() : parameters;
        Map<String, Class<?>> identifying = given.getIdentifyingParameters().entrySet().stream()
            .collect(toMap(Map.Entry::getKey, entry -> entry.getValue().getType()));
        if (given.getParameters().size() != identifying.size() || !identifying.equals(signature)) {
            throw new JobParametersInvalidException("식별 parameter " + signature + " 만 받는다: " + given);
        }
    }
}
