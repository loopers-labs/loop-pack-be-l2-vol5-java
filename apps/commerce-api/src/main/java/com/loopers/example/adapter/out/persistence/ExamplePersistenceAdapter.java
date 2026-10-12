package com.loopers.example.adapter.out.persistence;

import com.loopers.example.application.port.out.ExamplePort;
import com.loopers.example.domain.ExampleModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class ExamplePersistenceAdapter implements ExamplePort {
    private final ExampleJpaRepository exampleJpaRepository;

    @Override
    public Optional<ExampleModel> find(Long id) {
        return exampleJpaRepository.findById(id);
    }
}
