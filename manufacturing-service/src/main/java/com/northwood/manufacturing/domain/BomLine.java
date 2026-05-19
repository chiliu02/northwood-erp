package com.northwood.manufacturing.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal entity of the {@link Bom} aggregate — a single line in a bill of
 * materials. Identified by {@link BomLineId}; the position within its parent
 * BOM is tracked by {@code lineNumber} (assigned by the aggregate on
 * {@link Bom#addLine}).
 */
public final class BomLine {

    /** Input shape for {@link Bom#addLine}. */
    public record Spec(
        UUID componentProductId,
        String componentSku,
        String componentName,
        Bom.ComponentKind componentKind,
        BigDecimal quantityPerFinishedUnit,
        BigDecimal scrapFactorPercent
    ) {}

    private final BomLineId id;
    private final int lineNumber;
    private final UUID componentProductId;
    private final String componentSku;
    private final String componentName;
    private final Bom.ComponentKind componentKind;
    private final BigDecimal quantityPerFinishedUnit;
    private final BigDecimal scrapFactorPercent;

    public BomLine(
        BomLineId id,
        int lineNumber,
        UUID componentProductId,
        String componentSku,
        String componentName,
        Bom.ComponentKind componentKind,
        BigDecimal quantityPerFinishedUnit,
        BigDecimal scrapFactorPercent
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.lineNumber = lineNumber;
        this.componentProductId = Objects.requireNonNull(componentProductId, "componentProductId");
        this.componentSku = Objects.requireNonNull(componentSku, "componentSku");
        this.componentName = Objects.requireNonNull(componentName, "componentName");
        this.componentKind = Objects.requireNonNull(componentKind, "componentKind");
        this.quantityPerFinishedUnit = Objects.requireNonNull(quantityPerFinishedUnit, "quantityPerFinishedUnit");
        this.scrapFactorPercent = scrapFactorPercent == null ? BigDecimal.ZERO : scrapFactorPercent;
    }

    public BomLineId id()                       { return id; }
    public int lineNumber()                     { return lineNumber; }
    public UUID componentProductId()            { return componentProductId; }
    public String componentSku()                { return componentSku; }
    public String componentName()               { return componentName; }
    public Bom.ComponentKind componentKind()    { return componentKind; }
    public BigDecimal quantityPerFinishedUnit() { return quantityPerFinishedUnit; }
    public BigDecimal scrapFactorPercent()      { return scrapFactorPercent; }
}
