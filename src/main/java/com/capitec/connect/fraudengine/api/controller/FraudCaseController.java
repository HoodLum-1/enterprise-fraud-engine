package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.domain.model.CaseStatus;
import com.capitec.connect.fraudengine.engine.service.FraudCaseService;
import com.capitec.connect.fraudengine.api.dto.response.FraudCaseResponseDto;
import com.capitec.connect.fraudengine.api.exception.TransactionNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.Authentication;

@Slf4j
@RestController
@RequestMapping("/api/v1/fraud-cases")
@PreAuthorize("hasRole('FRAUD_ANALYST')")
@RequiredArgsConstructor
public class FraudCaseController {

    private final FraudCaseService fraudCaseService;

    @Operation(summary = "Retrieve all fraud cases", description = "Returns a list of all fraud cases in the system")
    @GetMapping
    public ResponseEntity<Page<FraudCaseResponseDto>> getAllCases(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        log.info("Fetching all fraud cases");
        return ResponseEntity.ok(fraudCaseService.findAll(pageable).map(FraudCaseResponseDto::from));
    }

    @Operation(summary = "Retrieve a fraud case by transaction ID", description = "Returns the fraud case associated with the given transaction ID")
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<FraudCaseResponseDto> getCaseByTransactionId(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable("transactionId") UUID transactionId) {
        log.info("Fetching fraud case for transaction ID: {}", transactionId);
        FraudCase fraudCase = fraudCaseService.findByTransactionId(transactionId);
        if (fraudCase == null) {
            throw new TransactionNotFoundException("Fraud case not found for transaction: " + transactionId);
        }
        return ResponseEntity.ok(FraudCaseResponseDto.from(fraudCase));
    }

    @Operation(summary = "Update fraud case status", description = "Updates the status of a fraud case. Valid statuses: CONFIRMED_FRAUD, FALSE_POSITIVE, RESOLVED")
    @PatchMapping("/{transactionId}/status")
    @ApiResponse(responseCode = "200", description = "Status updated successfully")
    @ApiResponse(responseCode = "404", description = "Fraud case not found")
    @ApiResponse(responseCode = "400", description = "Invalid status value")
    public ResponseEntity<FraudCaseResponseDto> updateStatus(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable("transactionId") UUID transactionId,
            @Parameter(description = "New status", required = true) @RequestParam("status") CaseStatus status,
            Authentication authentication) {
        FraudCase updated = fraudCaseService.updateStatus(transactionId, status, authentication.getName());
        return ResponseEntity.ok(FraudCaseResponseDto.from(updated));
    }
}