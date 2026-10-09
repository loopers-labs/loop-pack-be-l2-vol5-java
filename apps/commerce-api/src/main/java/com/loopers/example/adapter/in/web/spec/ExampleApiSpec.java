package com.loopers.example.adapter.in.web.spec;

import com.loopers.example.adapter.in.web.dto.ExampleDto;
import com.loopers.support.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Example V1 API", description = "Loopers 예시 API 입니다.")
public interface ExampleApiSpec {

    @Operation(
        summary = "예시 조회",
        description = "ID로 예시를 조회합니다."
    )
    ApiResponse<ExampleDto.ExampleResponse> getExample(
        @Schema(name = "예시 ID", description = "조회할 예시의 ID")
        Long exampleId
    );
}
