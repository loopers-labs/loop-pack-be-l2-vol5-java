package com.loopers.interfaces.api.pg;

import com.loopers.domain.pg.model.PgScenario;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 카드 필드를 포함하므로 record의 자동 toString으로 원문을 출력하지 않는다.
public record CreatePgPaymentRequest(
        @NotBlank @Size(min = 6, max = 100) String orderId,
        @NotNull @Min(0) Long amount,
        @NotNull @Pattern(regexp = "TEST") String cardType,
        @NotNull @Pattern(regexp = "0000-0000-0000-0000") String cardNo,
        @NotBlank @Size(max = 2048) String callbackUrl,
        TestOptions testOptions
) {
    public PgScenario scenario() {
        return testOptions == null ? PgScenario.resolve(null, null)
                : PgScenario.resolve(testOptions.preset(), testOptions.overrides());
    }

    @Override
    public String toString() {
        return "CreatePgPaymentRequest[redacted]";
    }

    public record TestOptions(PgScenario.Preset preset, PgScenario.Overrides overrides) {}
}
