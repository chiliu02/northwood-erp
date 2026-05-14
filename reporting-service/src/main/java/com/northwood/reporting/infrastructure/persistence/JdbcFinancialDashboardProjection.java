package com.northwood.reporting.infrastructure.persistence;

import com.northwood.reporting.application.inbox.FinancialDashboardProjection;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcFinancialDashboardProjection implements FinancialDashboardProjection {

    private static final String DEFAULT_CURRENCY = "AUD";

    private final JdbcTemplate jdbc;

    public JdbcFinancialDashboardProjection(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void recordCustomerInvoice(BigDecimal amount, String currencyCode, Instant occurredAt) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        if (v.signum() <= 0) return;
        upsertMoney("sales_revenue", v, currencyCode, occurredAt, true);
    }

    @Override
    @Transactional
    public void recordSupplierInvoice(BigDecimal amount, String currencyCode, Instant occurredAt) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        if (v.signum() <= 0) return;
        upsertMoney("cost_of_goods_sold", v, currencyCode, occurredAt, true);
    }

    @Override
    @Transactional
    public void recordCustomerPayment(BigDecimal amount, String currencyCode, Instant occurredAt) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        if (v.signum() <= 0) return;
        upsertMoney("cash_received", v, currencyCode, occurredAt, false);
    }

    @Override
    @Transactional
    public void recordSupplierPayment(BigDecimal amount, String currencyCode, Instant occurredAt) {
        BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
        if (v.signum() <= 0) return;
        upsertMoney("cash_paid", v, currencyCode, occurredAt, false);
    }

    @Override
    @Transactional
    public void recordSalesOrderPlaced(String currencyCode, Instant occurredAt) {
        upsertCount("open_sales_orders_count", currencyCode, occurredAt);
    }

    @Override
    @Transactional
    public void recordPurchaseOrderCreated(String currencyCode, Instant occurredAt) {
        upsertCount("open_purchase_orders_count", currencyCode, occurredAt);
    }

    @Override
    @Transactional
    public void recordWorkOrderCreated(String currencyCode, Instant occurredAt) {
        upsertCount("open_work_orders_count", currencyCode, occurredAt);
    }

    private void upsertMoney(String column, BigDecimal delta, String currencyCode,
                              Instant occurredAt, boolean recomputeGross) {
        LocalDate date = (occurredAt == null ? Instant.now() : occurredAt)
            .atZone(ZoneId.systemDefault()).toLocalDate();
        String currency = currencyCode == null ? DEFAULT_CURRENCY : currencyCode;
        String grossUpdate = recomputeGross
            ? "gross_profit = (financial_dashboard_daily.sales_revenue + CASE WHEN 'COL' = 'sales_revenue' THEN ? ELSE 0 END)"
              + " - (financial_dashboard_daily.cost_of_goods_sold + CASE WHEN 'COL' = 'cost_of_goods_sold' THEN ? ELSE 0 END),"
            : "";
        String sql = ("""
            INSERT INTO reporting.financial_dashboard_daily (
                dashboard_date, currency_code,
                sales_revenue, cost_of_goods_sold, gross_profit,
                inventory_value, wip_value,
                accounts_receivable, accounts_payable,
                cash_received, cash_paid,
                open_sales_orders_count, open_purchase_orders_count, open_work_orders_count,
                updated_at
            ) VALUES (?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, now())
            ON CONFLICT (dashboard_date, currency_code) DO UPDATE SET
                COL = financial_dashboard_daily.COL + ?,
                """ + grossUpdate + """
                updated_at = now()
            """).replace("COL", column);
        if (recomputeGross) {
            jdbc.update(sql,
                Date.valueOf(date), currency,
                delta, delta, delta);
        } else {
            jdbc.update(sql,
                Date.valueOf(date), currency,
                delta);
        }
    }

    private void upsertCount(String column, String currencyCode, Instant occurredAt) {
        LocalDate date = (occurredAt == null ? Instant.now() : occurredAt)
            .atZone(ZoneId.systemDefault()).toLocalDate();
        String currency = currencyCode == null ? DEFAULT_CURRENCY : currencyCode;
        String sql = ("""
            INSERT INTO reporting.financial_dashboard_daily (
                dashboard_date, currency_code,
                sales_revenue, cost_of_goods_sold, gross_profit,
                inventory_value, wip_value,
                accounts_receivable, accounts_payable,
                cash_received, cash_paid,
                open_sales_orders_count, open_purchase_orders_count, open_work_orders_count,
                updated_at
            ) VALUES (?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, now())
            ON CONFLICT (dashboard_date, currency_code) DO UPDATE SET
                COL = financial_dashboard_daily.COL + 1,
                updated_at = now()
            """).replace("COL", column);
        jdbc.update(sql, Date.valueOf(date), currency);
    }
}
