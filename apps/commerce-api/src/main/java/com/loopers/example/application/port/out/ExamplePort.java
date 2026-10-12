package com.loopers.example.application.port.out;

import com.loopers.example.domain.ExampleModel;
import java.util.Optional;

public interface ExamplePort {
    Optional<ExampleModel> find(Long id);
}
