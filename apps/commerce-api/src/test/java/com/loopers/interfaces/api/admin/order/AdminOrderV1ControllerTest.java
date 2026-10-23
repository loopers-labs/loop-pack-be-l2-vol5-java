package com.loopers.interfaces.api.admin.order;

import com.loopers.application.order.AdminOrderFacade;
import com.loopers.application.order.OrderInfo;
import com.loopers.config.AdminRequestInterceptor;
import com.loopers.config.WebMvcConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminOrderV1Controller.class)
@Import({WebMvcConfig.class, AdminRequestInterceptor.class})
class AdminOrderV1ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminOrderFacade adminOrderFacade;

    @Test
    void returnsOrdersForAdminView() throws Exception {
        when(adminOrderFacade.getAll()).thenReturn(List.of(
            new OrderInfo(1L, 10L, "CONFIRMED", 7_000L, 7_000L, "PG", "CONSUMED", List.of(),
                new OrderInfo.PaymentSummary("SUCCESS", "LP-ORD-0000000001", "TEST-TX-1"))
        ));

        mockMvc.perform(get("/api-admin/v1/orders").header("X-USER-ROLE", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].userId").value(10))
            .andExpect(jsonPath("$.data[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data[0].paidAmount").value(7_000))
            .andExpect(jsonPath("$.data[0].paymentMethod").value("PG"))
            .andExpect(jsonPath("$.data[0].paymentStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data[0].providerOrderId").value("LP-ORD-0000000001"))
            .andExpect(jsonPath("$.data[0].transactionKey").value("TEST-TX-1"));
    }
}
