package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.api.dto.request.TransactionRequestDto;
import com.capitec.connect.fraudengine.api.dto.response.EvaluationSummaryResponseDto;
import com.capitec.connect.fraudengine.api.dto.response.FraudAlertResponseDto;
import com.capitec.connect.fraudengine.domain.model.EvaluationSummary;
import com.capitec.connect.fraudengine.engine.service.FraudEvaluationService;
import com.capitec.connect.fraudengine.infrastructure.mapper.FraudAlertMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final FraudEvaluationService fraudEvaluationService;
    private final FraudAlertMapper fraudAlertMapper;

    @PostMapping("/evaluate")
    public ResponseEntity<EvaluationSummaryResponseDto> evaluateTransaction(
            @Valid @RequestBody TransactionRequestDto requestDto) {
        log.info("Received transaction evaluation request for transaction id: {}", requestDto.id());

        EvaluationSummary summary = fraudEvaluationService.evaluate(requestDto);
        EvaluationSummaryResponseDto responseDto = toResponseDto(summary);

        return ResponseEntity.status(HttpStatus.CREATED).body(responseDto);
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
