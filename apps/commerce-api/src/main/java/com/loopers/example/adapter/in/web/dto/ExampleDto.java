package com.loopers.example.adapter.in.web.dto;

import com.loopers.example.application.port.in.ExampleInfo;

public class ExampleDto {
    public record ExampleResponse(Long id, String name, String description) {
        public static ExampleResponse from(ExampleInfo info) {
            return new ExampleResponse(
                info.id(),
                info.name(),
                info.description()
            );
        }
    }
}
