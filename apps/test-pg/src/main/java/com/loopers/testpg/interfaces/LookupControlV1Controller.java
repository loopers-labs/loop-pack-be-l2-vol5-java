package com.loopers.testpg.interfaces;

import com.loopers.testpg.application.LookupScenarioService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/test-control/v1/lookup")
@ConditionalOnProperty(prefix = "test-pg.control", name = "enabled", havingValue = "true")
public class LookupControlV1Controller {
    private final LookupScenarioService service;
    private final String providerAccountId;

    public LookupControlV1Controller(LookupScenarioService service,
                                    @Value("${test-pg.provider-account-id}") String providerAccountId) {
        this.service = service;
        this.providerAccountId = providerAccountId;
    }

    @GetMapping
    public LookupControlV1Dto.SettingResponse current(@RequestHeader("X-USER-ID") String accountId) {
        verifyAccount(accountId);
        return LookupControlV1Dto.SettingResponse.from(service.current());
    }

    @PutMapping
    public ResponseEntity<Void> configure(@RequestHeader("X-USER-ID") String accountId,
                                          @RequestBody LookupControlV1Dto.SettingRequest request) {
        verifyAccount(accountId);
        service.configure(request.toScenario());
        return ResponseEntity.noContent().build();
    }

    private void verifyAccount(String accountId) {
        if (!providerAccountId.equals(accountId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED_PROVIDER");
        }
    }
}
