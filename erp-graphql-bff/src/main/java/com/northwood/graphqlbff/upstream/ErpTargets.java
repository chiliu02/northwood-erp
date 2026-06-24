package com.northwood.graphqlbff.upstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-target service URLs, populated from {@code northwood.bff.targets.*}.
 * Only the services the GraphQL graph traverses are bound (product, sales,
 * inventory, reporting, search); finance is kept for parity/future fields.
 * Mirrors {@code erp-web-ui-bff}'s {@code ErpBffTargets} (the BFF deliberately
 * carries no dependency on the service modules).
 */
@ConfigurationProperties(prefix = "northwood.bff.targets")
public class ErpTargets {

    private String product;
    private String sales;
    private String inventory;
    private String finance;
    private String reporting;
    private String search;

    public String forName(String name) {
        return switch (name) {
            case "product"   -> product;
            case "sales"     -> sales;
            case "inventory" -> inventory;
            case "finance"   -> finance;
            case "reporting" -> reporting;
            case "search"    -> search;
            default -> throw new IllegalArgumentException("Unknown GraphQL BFF target: " + name);
        };
    }

    public String getProduct()        { return product; }
    public void setProduct(String v)  { this.product = v; }
    public String getSales()          { return sales; }
    public void setSales(String v)    { this.sales = v; }
    public String getInventory()      { return inventory; }
    public void setInventory(String v) { this.inventory = v; }
    public String getFinance()        { return finance; }
    public void setFinance(String v)  { this.finance = v; }
    public String getReporting()      { return reporting; }
    public void setReporting(String v) { this.reporting = v; }
    public String getSearch()         { return search; }
    public void setSearch(String v)   { this.search = v; }
}
