package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.engine.service.FraudCaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/fraud-cases")
@RequiredArgsConstructor
public class FraudCaseController {

    private final FraudCaseService fraudCaseService;

    @Operation(summary = "Retrieve all fraud cases", description = "Returns a list of all fraud cases in the system")
    @GetMapping
    public ResponseEntity<List<FraudCase>> getAllCases() {
        log.info("Fetching all fraud cases");
        return ResponseEntity.ok(fraudCaseService.findAll());
    }

    @Operation(summary = "Retrieve a fraud case by transaction ID", description = "Returns the fraud case associated with the given transaction ID")
    @GetMapping("/transaction/{transactionId}")
    public ResponseEntity<FraudCase> getCaseByTransactionId(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable("transactionId") UUID transactionId) {
        log.info("Fetching fraud case for transaction ID: {}", transactionId);
        FraudCase fraudCase = fraudCaseService.findByTransactionId(transactionId);
        if (fraudCase == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(fraudCase);
    }

    @Operation(summary = "Update fraud case status", description = "Updates the status of a fraud case. Valid statuses: CONFIRMED_FRAUD, FALSE_POSITIVE, RESOLVED")
    @PatchMapping("/{transactionId}/status")
    @ApiResponse(responseCode = "200", description = "Status updated successfully")
    @ApiResponse(responseCode = "404", description = "Fraud case not found")
    @ApiResponse(responseCode = "400", description = "Invalid status value")
    public ResponseEntity<FraudCase> updateStatus(
            @Parameter(description = "Transaction UUID", required = true) @PathVariable("transactionId") UUID transactionId,
            @Parameter(description = "New status", required = true) @RequestParam("status") @Size(max = 30) String status) {

        List<String> validStatuses = List.of("CONFIRMED_FRAUD", "FALSE_POSITIVE", "RESOLVED", "PENDING_REVIEW");
        if (!validStatuses.contains(status)) {
            log.warn("Invalid status value provided: {}", status);
            return ResponseEntity.badRequest().build();
        }

        try {
            FraudCase updated = fraudCaseService.updateStatus(transactionId, status);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }
}