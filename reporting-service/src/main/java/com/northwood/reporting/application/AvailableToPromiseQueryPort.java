package com.northwood.reporting.application;

import com.northwood.reporting.application.dto.AvailableToPromiseView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AvailableToPromiseQueryPort {

    List<AvailableToPromiseView> findAll();

    Optional<AvailableToPromiseView> findByProductId(UUID productId);
}
