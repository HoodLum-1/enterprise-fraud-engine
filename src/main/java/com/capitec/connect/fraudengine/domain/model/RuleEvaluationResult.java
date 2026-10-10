package com.capitec.connect.fraudengine.domain.model;

public record RuleEvaluationResult(
    String ruleName,
    boolean isTriggered,
    int riskScore,
    Severity severity,
    String reason
) {}
