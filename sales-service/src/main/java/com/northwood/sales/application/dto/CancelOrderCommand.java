package com.northwood.sales.application.dto;

import java.util.UUID;

public record CancelOrderCommand(UUID salesOrderHeaderId, String reason) {}
