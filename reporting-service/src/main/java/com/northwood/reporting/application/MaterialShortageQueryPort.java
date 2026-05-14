package com.northwood.reporting.application;

import com.northwood.reporting.application.dto.MaterialShortageView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MaterialShortageQueryPort {

    /** Returns all rows in the shortage view, ordered by status (open first) then SKU. */
    List<MaterialShortageView> findAll();

    /** Returns only rows whose status is not 'resolved' — the active shortage list. */
    List<MaterialShortageView> findActive();

    Optional<MaterialShortageView> findByProductId(UUID materialProductId);
}
