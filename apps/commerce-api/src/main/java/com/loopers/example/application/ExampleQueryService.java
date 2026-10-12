package com.loopers.example.application;

import com.loopers.example.application.port.in.ExampleInfo;
import com.loopers.example.application.port.out.ExamplePort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class ExampleQueryService {

    private final ExamplePort examplePort;

    @Transactional(readOnly = true)
    public ExampleInfo getExample(Long id) {
        return examplePort.find(id)
            .map(ExampleInfo::from)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 예시를 찾을 수 없습니다."));
    }
}
