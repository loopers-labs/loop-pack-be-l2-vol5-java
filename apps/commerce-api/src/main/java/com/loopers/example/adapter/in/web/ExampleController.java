package com.loopers.example.adapter.in.web;

import com.loopers.example.adapter.in.web.dto.ExampleDto;
import com.loopers.example.adapter.in.web.spec.ExampleApiSpec;
import com.loopers.example.application.ExampleQueryService;
import com.loopers.example.application.port.in.ExampleInfo;
import com.loopers.support.web.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/examples")
public class ExampleController implements ExampleApiSpec {

    private final ExampleQueryService exampleQueryService;

    @GetMapping("/{exampleId}")
    @Override
    public ApiResponse<ExampleDto.ExampleResponse> getExample(
        @PathVariable(value = "exampleId") Long exampleId
    ) {
        ExampleInfo info = exampleQueryService.getExample(exampleId);
        ExampleDto.ExampleResponse response = ExampleDto.ExampleResponse.from(info);
        return ApiResponse.success(response);
    }
}
