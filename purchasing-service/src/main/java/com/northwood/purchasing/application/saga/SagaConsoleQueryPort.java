package com.northwood.purchasing.application.saga;

import com.northwood.purchasing.application.dto.SagaRowView;
import java.util.List;

/**
 * Read-only port for the Saga Console — returns purchase-to-pay saga rows
 * in {@code updated_at DESC} order. JDBC adapter at
 * {@code infrastructure/persistence/JdbcSagaConsoleQueryPort}. The controller
 * maps the View to the wire {@code SagaRow} DTO at the api boundary.
 */
public interface SagaConsoleQueryPort {

    List<SagaRowView> listSagas();
}
