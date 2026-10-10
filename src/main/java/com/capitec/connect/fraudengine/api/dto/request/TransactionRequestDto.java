package com.capitec.connect.fraudengine.api.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionRequestDto(
    @NotNull(message = "Transaction ID is required")
    UUID id,

    @NotBlank(message = "Account ID is required")
    @Size(max = 64, message = "Account ID must not exceed 64 characters")
    String accountId,

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be positive")
    @Digits(integer = 13, fraction = 2, message = "Amount must have up to 13 integer digits and 2 decimal places")
    BigDecimal amount,

    @NotBlank(message = "Currency is required")
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a valid 3-letter ISO code")
    String currency,

    @NotBlank(message = "Category is required")
    String category,

    String merchantName,

    String location,

    @NotNull(message = "Timestamp is required")
    @PastOrPresent(message = "Timestamp must be in the past or present")
    Instant timestamp
) {
}
