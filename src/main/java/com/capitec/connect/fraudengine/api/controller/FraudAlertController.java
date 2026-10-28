package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.api.exception.ResourceNotFoundException;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.engine.service.FraudAlertQueryService;
import com.capitec.connect.fraudengine.infrastructure.logging.LogMasking;
import com.capitec.connect.fraudengine.infrastructure.security.AlertVisibility;
import com.capitec.connect.fraudengine.infrastructure.security.AccountAccessPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

@Slf4j
@RestController
@RequestMapping("/api/v1/fraud-alerts")
@RequiredArgsConstructor
public class FraudAlertController {

    private final FraudAlertQueryService fraudAlertQueryService;
    private final AccountAccessPolicy accountAccessPolicy;
    private final AlertVisibility alertVisibility;

    @GetMapping("/account/{accountId}")
    public ResponseEntity<Page<FraudAlertResponseDto>> getAlertsByAccountId(
            @PathVariable String accountId,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String ruleName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "flaggedAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication) {
        accountAccessPolicy.verifyAccess(accountId, authentication, ResourceNotFoundException::new);
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("'from' must be earlier than or equal to 'to'");
        }
        log.info("Fetching fraud alerts for account: {}", LogMasking.mask(accountId));
        Page<FraudAlertResponseDto> alerts = fraudAlertQueryService.getAlertsByAccountId(
            accountId, severity, ruleName, from, to, pageable);
        return ResponseEntity.ok(alerts.map(alert -> alertVisibility.visibleTo(alert, authentication)));
    }

}
