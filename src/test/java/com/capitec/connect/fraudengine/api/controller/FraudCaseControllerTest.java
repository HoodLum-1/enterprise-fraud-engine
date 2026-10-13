package com.capitec.connect.fraudengine.api.controller;

import com.capitec.connect.fraudengine.domain.model.FraudCase;
import com.capitec.connect.fraudengine.domain.model.Severity;
import com.capitec.connect.fraudengine.engine.service.FraudCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
    void getAllCases_returnsAllCases() {
        when(fraudCaseService.findAll()).thenReturn(java.util.Collections.emptyList());

        var result = fraudCaseController.getAllCases();

        assertNotNull(result);
        assertEquals(0, result.getBody().size());
        verify(fraudCaseService).findAll();
    }

    @Test
    void getCaseByTransactionId_existing_returnsCase() {
        UUID txId = UUID.randomUUID();
        FraudCase fraudCase = new FraudCase(txId, "ACC-1001", 40, Severity.HIGH);
        when(fraudCaseService.findByTransactionId(txId)).thenReturn(fraudCase);

        var result = fraudCaseController.getCaseByTransactionId(txId);

        assertNotNull(result);
        assertNotNull(result.getBody());
        assertEquals(txId, result.getBody().transactionId());
        assertEquals("ACC-1001", result.getBody().accountId());
        verify(fraudCaseService).findByTransactionId(txId);
    }

    @Test
    void getCaseByTransactionId_unknown_returnsNotFound() {
        when(fraudCaseService.findByTransactionId(any())).thenReturn(null);

        var result = fraudCaseController.getCaseByTransactionId(UUID.randomUUID());

        assertNotNull(result);
        assertEquals(404, result.getStatusCode().value());
        verify(fraudCaseService).findByTransactionId(any());
    }

    @Test
    void updateStatus_valid_returnsUpdatedCase() {
        UUID txId = UUID.randomUUID();
        FraudCase fraudCase = new FraudCase(txId, "ACC-1001", 80, Severity.CRITICAL);
        when(fraudCaseService.updateStatus(txId, "CONFIRMED_FRAUD"))
                .thenReturn(new FraudCase(UUID.randomUUID(), txId, "ACC-1001", 80, Severity.CRITICAL, "CONFIRMED_FRAUD", null, null));

        var result = fraudCaseController.updateStatus(txId, "CONFIRMED_FRAUD");

        assertNotNull(result);
        assertNotNull(result.getBody());
        assertEquals("CONFIRMED_FRAUD", result.getBody().status());
        verify(fraudCaseService).updateStatus(txId, "CONFIRMED_FRAUD");
    }

    @Test
    void updateStatus_invalid_status_returnsBadRequest() {
        var result = fraudCaseController.updateStatus(UUID.randomUUID(), "INVALID_STATUS");

        assertNotNull(result);
        assertEquals(400, result.getStatusCode().value());
    }

    @Test
    void updateStatus_unknownTransaction_returnsNotFound() {
        when(fraudCaseService.updateStatus(any(), any()))
                .thenThrow(new IllegalArgumentException("Not found"));

        var result = fraudCaseController.updateStatus(UUID.randomUUID(), "CONFIRMED_FRAUD");

        assertNotNull(result);
        assertEquals(404, result.getStatusCode().value());
    }
}