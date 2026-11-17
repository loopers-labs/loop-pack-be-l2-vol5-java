package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.coupon.CouponException;
import com.loopers.interfaces.api.commerce.CommerceErrors;
import com.loopers.interfaces.api.commerce.CommerceWebConfiguration;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CouponAdmissionHttpContractTest {
    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final CommerceErrors errors = new CommerceErrors(mapper);

    @Test
    void invalidCouponRequestsUseTheExistingCommerceErrorEnvelope() throws Exception {
        assertFailure(new InvalidRequestException(), 400, "INVALID_REQUEST");
    }

    @ParameterizedTest
    @CsvSource({
        "SOLD_OUT,409,COUPON_CAMPAIGN_SOLD_OUT",
        "INACTIVE,409,COUPON_CAMPAIGN_INACTIVE",
        "CAMPAIGN_NOT_FOUND,404,COUPON_CAMPAIGN_NOT_FOUND",
        "ADMISSION_UNAVAILABLE,503,COUPON_ADMISSION_UNAVAILABLE"
    })
    void separatesDefinitiveBusinessRejectionsFromUncertainAdmission(CouponException.Reason reason, int status,
                                                                     String code) throws Exception {
        assertFailure(new CouponException(reason), status, code);
    }

    private void assertFailure(Exception exception, int status, String errorCode) throws Exception {
        List<HandlerExceptionResolver> resolvers = new ArrayList<>();
        new CommerceWebConfiguration(errors).extendHandlerExceptionResolvers(resolvers);
        var request = new MockHttpServletRequest("POST", "/api/v1/coupon-campaigns/42/issues");
        var response = new MockHttpServletResponse();
        var result = resolvers.getFirst().resolveException(request, response, null, exception);
        assertThat(result).isNotNull();
        assertThat(response.getStatus()).isEqualTo(status);
        var json = mapper.readTree(response.getContentAsByteArray());
        assertThat(json.path("meta").path("result").asText()).isEqualTo("FAIL");
        assertThat(json.path("meta").path("errorCode").asText()).isEqualTo(errorCode);
        assertThat(json.has("data")).isFalse();
    }
}
