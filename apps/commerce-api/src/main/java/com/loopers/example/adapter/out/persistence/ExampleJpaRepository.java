package com.loopers.example.adapter.out.persistence;

import com.loopers.example.domain.ExampleModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExampleJpaRepository extends JpaRepository<ExampleModel, Long> {}
