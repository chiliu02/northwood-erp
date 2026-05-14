package com.northwood.finance.application.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Application-layer read shape returned by
 * {@code ExchangeRateService.rate}. Mirrors the controller's wire fields
 * without exposing the domain {@code CurrencyConverter.RateSnapshot}.
 */
public record RateView(BigDecimal rate, LocalDate effectiveDate) {}
