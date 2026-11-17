package com.loopers.interfaces.api.coupon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loopers.application.coupon.support.CouponAdmissionException;
import com.loopers.application.coupon.support.CouponSlotBusyException;
import com.loopers.domain.coupon.exception.CouponIssueLockBusyException;
import com.loopers.interfaces.api.ApiErrorMapper;
import com.loopers.interfaces.api.coupon.support.CouponApiControllerAdvice;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 응답 경계만 분리해서 검증한다. 실제 발급과 수용 동작은 CouponApiE2ETest에서 검증한다. */
class CouponErrorMappingTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ErrorController())
        .setControllerAdvice(new CouponApiControllerAdvice(new ApiErrorMapper())).build();

    @ParameterizedTest
    @ValueSource(strings = {"COUPON_SLOT_BUSY", "COUPON_LOCK_BUSY", "COUPON_ISSUE_OUTCOME_UNKNOWN"})
    void mapsTransientAndUnknownOutcomesWithoutExposingInternalCause(String code) throws Exception {
        mvc.perform(get("/coupon-error/" + code))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.meta.errorCode").value(code))
            .andExpect(jsonPath("$.data").isEmpty());
    }

    @RestController
    @TestConfiguration(proxyBeanMethods = false)
    static class ErrorController {
        @GetMapping("/coupon-error/{code}")
        public void error(@PathVariable String code) {
            throw switch (code) {
                case "COUPON_SLOT_BUSY" -> new CouponSlotBusyException();
                case "COUPON_LOCK_BUSY" -> new CouponIssueLockBusyException(new IllegalStateException("internal DB cause"));
                default -> new CouponAdmissionException(CouponAdmissionException.Code.COUPON_ISSUE_OUTCOME_UNKNOWN);
            };
        }
    }
}
