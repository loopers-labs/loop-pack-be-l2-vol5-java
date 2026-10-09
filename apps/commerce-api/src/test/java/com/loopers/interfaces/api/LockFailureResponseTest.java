package com.loopers.interfaces.api;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LockFailureResponseTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailureController())
        .setControllerAdvice(new ApiControllerAdvice()).build();

    @Test
    void translatedLockTimeoutReturnsServiceUnavailable() throws Exception {
        mvc.perform(get("/test/timeout")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("meta.errorCode").value("Service Unavailable"));
    }

    @Test
    void translatedDeadlockReturnsServiceUnavailable() throws Exception {
        mvc.perform(get("/test/deadlock")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void unrelatedStorageFailureRemainsInternalServerError() throws Exception {
        mvc.perform(get("/test/storage-error")).andExpect(status().isInternalServerError());
    }

    @RestController
    static class FailureController {
        @GetMapping("/test/timeout")
        void timeout() {
            throw new CannotAcquireLockException("test timeout");
        }

        @GetMapping("/test/deadlock")
        void deadlock() {
            throw new PessimisticLockingFailureException("test deadlock");
        }

        @GetMapping("/test/storage-error")
        void storageError() {
            throw new DataIntegrityViolationException("test unrelated constraint");
        }
    }
}
