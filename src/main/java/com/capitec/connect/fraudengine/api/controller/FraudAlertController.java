package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.engine.service.FraudAlertQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/fraud-alerts")
@RequiredArgsConstructor
public class FraudAlertController {

    private final FraudAlertQueryService fraudAlertQueryService;

    @GetMapping("/account/{accountId}")
    public ResponseEntity<List<FraudAlertResponseDto>> getAlertsByAccountId(@PathVariable String accountId) {
        log.info("Fetching fraud alerts for account: {}", accountId);
        List<FraudAlertResponseDto> responseDtos = fraudAlertQueryService.getAlertsByAccountId(accountId);
        return ResponseEntity.ok(responseDtos);
    }
}
