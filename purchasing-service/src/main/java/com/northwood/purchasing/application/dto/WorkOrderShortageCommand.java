package com.northwood.purchasing.application.dto;

import java.util.List;
import java.util.UUID;

public record WorkOrderShortageCommand(
    String requisitionNumber,
    UUID workOrderId,
    List<RequisitionLineRequest> lines
) {}
