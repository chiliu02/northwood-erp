package com.northwood.bff;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-target service URLs, populated from {@code northwood.bff.targets.*}.
 * Resolved by service name; the routing table in {@link RouteTable} maps
 * path prefixes to these names.
 */
@ConfigurationProperties(prefix = "northwood.bff.targets")
public class BffTargets {

    private String product;
    private String sales;
    private String inventory;
    private String manufacturing;
    private String purchasing;
    private String finance;
    private String reporting;

    public String forName(String name) {
        return switch (name) {
            case "product"       -> product;
            case "sales"         -> sales;
            case "inventory"     -> inventory;
            case "manufacturing" -> manufacturing;
            case "purchasing"    -> purchasing;
            case "finance"       -> finance;
            case "reporting"     -> reporting;
            default -> throw new IllegalArgumentException("Unknown BFF target: " + name);
        };
    }

    public String getProduct()       { return product; }
    public void setProduct(String v) { this.product = v; }
    public String getSales()         { return sales; }
    public void setSales(String v)   { this.sales = v; }
    public String getInventory()     { return inventory; }
    public void setInventory(String v) { this.inventory = v; }
    public String getManufacturing() { return manufacturing; }
    public void setManufacturing(String v) { this.manufacturing = v; }
    public String getPurchasing()    { return purchasing; }
    public void setPurchasing(String v) { this.purchasing = v; }
    public String getFinance()       { return finance; }
    public void setFinance(String v) { this.finance = v; }
    public String getReporting()     { return reporting; }
    public void setReporting(String v) { this.reporting = v; }
}
