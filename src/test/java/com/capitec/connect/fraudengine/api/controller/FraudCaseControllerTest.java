package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.domain.model.CaseStatus;
import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.engine.service.FraudCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FraudCaseControllerTest {

    @Mock
    private FraudCaseService fraudCaseService;

    @InjectMocks
    private FraudCaseController fraudCaseController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void getAllCases_returnsPage() {
        when(fraudCaseService.findAll(any())).thenReturn(Page.empty());

        var result = fraudCaseController.getAllCases(PageRequest.of(0, 20));

        assertNotNull(result.getBody());
        assertEquals(0, result.getBody().getTotalElements());
        verify(fraudCaseService).findAll(PageRequest.of(0, 20));
    }

    @Test
    void getCaseByTransactionId_existing_returnsResponseDto() {
        UUID txId = UUID.randomUUID();
        FraudCase fraudCase = new FraudCase(txId, "ACC-1001", 40, Severity.HIGH);
        when(fraudCaseService.findByTransactionId(txId)).thenReturn(fraudCase);

        var result = fraudCaseController.getCaseByTransactionId(txId);

        assertNotNull(result.getBody());
        assertEquals(txId, result.getBody().transactionId());
        assertEquals("ACC-1001", result.getBody().accountId());
        assertEquals(CaseStatus.PENDING_REVIEW, result.getBody().status());
    }

    @Test
    void getCaseByTransactionId_unknown_throwsNotFoundException() {
        when(fraudCaseService.findByTransactionId(any())).thenReturn(null);

        assertThrows(com.capitec.connect.fraudengine.api.exception.TransactionNotFoundException.class,
            () -> fraudCaseController.getCaseByTransactionId(UUID.randomUUID()));
    }

    @Test
    void updateStatus_valid_returnsUpdatedCaseAndActor() {
        UUID txId = UUID.randomUUID();
        var authentication = new UsernamePasswordAuthenticationToken("analyst", "n/a");
        FraudCase updated = new FraudCase(
            UUID.randomUUID(), txId, "ACC-1001", 80, Severity.CRITICAL,
            CaseStatus.CONFIRMED_FRAUD, null, null, "analyst", 1);
        when(fraudCaseService.updateStatus(txId, CaseStatus.CONFIRMED_FRAUD, "analyst")).thenReturn(updated);

        var result = fraudCaseController.updateStatus(txId, CaseStatus.CONFIRMED_FRAUD, authentication);

        assertNotNull(result.getBody());
        assertEquals(CaseStatus.CONFIRMED_FRAUD, result.getBody().status());
        assertEquals("analyst", result.getBody().updatedBy());
        verify(fraudCaseService).updateStatus(txId, CaseStatus.CONFIRMED_FRAUD, "analyst");
    }
}
