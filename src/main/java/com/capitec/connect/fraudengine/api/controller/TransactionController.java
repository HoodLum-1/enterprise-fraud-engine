package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.dto.response.EvaluationSummaryResponseDto;
import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.api.dto.response.TransactionDetailsResponseDto;
import com.capitec.connect.fraudengine.api.exception.TransactionNotFoundException;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.engine.service.HttpEvaluationService;
import com.capitec.connect.fraudengine.engine.service.TransactionQueryService;
import com.capitec.connect.fraudengine.infrastructure.security.AlertVisibility;
import com.capitec.connect.fraudengine.infrastructure.security.AccountAccessPolicy;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final HttpEvaluationService httpEvaluationService;
    private final FraudAlertMapper fraudAlertMapper;
    private final TransactionQueryService transactionQueryService;
    private final AccountAccessPolicy accountAccessPolicy;
    private final AlertVisibility alertVisibility;

    @PostMapping("/evaluate")
    public ResponseEntity<EvaluationSummaryResponseDto> evaluateTransaction(
            @Valid @RequestBody TransactionRequestDto requestDto) {
        log.info("Received transaction evaluation request for transaction id: {}", requestDto.id());

        EvaluationSummary summary = httpEvaluationService.evaluate(requestDto);
        EvaluationSummaryResponseDto responseDto = toResponseDto(summary);

        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionDetailsResponseDto> getTransaction(
            @PathVariable UUID transactionId,
            Authentication authentication) {
        TransactionDetailsResponseDto details = transactionQueryService.getDetails(transactionId);
        // A caller who may not read the account gets the same 404, with the same body, as a
        // transaction that does not exist. A 403 here would confirm the transaction exists and
        // merely belongs to somebody else.
        accountAccessPolicy.verifyAccess(details.accountId(), authentication,
            () -> new TransactionNotFoundException(transactionId));
        return ResponseEntity.ok(alertVisibility.visibleTo(details, authentication));
    }

    private EvaluationSummaryResponseDto toResponseDto(EvaluationSummary summary) {
        List<FraudAlertResponseDto> alertDtos = summary.alerts() != null
            ? summary.alerts().stream().map(fraudAlertMapper::toResponseDto).toList()
            : List.of();

        return new EvaluationSummaryResponseDto(
            summary.transactionId(),
            summary.accountId(),
            summary.totalRiskScore(),
            summary.overallSeverity(),
            summary.isFraudulent(),
            alertDtos
        );
    }
}
