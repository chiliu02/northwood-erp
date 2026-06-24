package com.northwood.graphqlbff.upstream;

import com.northwood.graphqlbff.dto.AvailableToPromiseView;
import com.northwood.graphqlbff.dto.CustomerView;
import com.northwood.graphqlbff.dto.PlaceOrderInput;
import com.northwood.graphqlbff.dto.ProductView;
import com.northwood.graphqlbff.dto.SalesOrder360View;
import com.northwood.graphqlbff.dto.SalesOrderView;
import com.northwood.graphqlbff.dto.StockItemView;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Blocking JDK-HttpClient wrapper for the upstream REST services, attaching the
 * Keycloak Bearer token and (de)serialising with the shared Jackson 3
 * {@link ObjectMapper}. Each call logs one line so DataLoader batching is
 * visible in the logs: a 10-line order still hits product / inventory /
 * reporting / customers exactly once apiece.
 */
@Component
public class UpstreamClient {

    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final ErpTargets targets;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    public UpstreamClient(ErpTargets targets, ObjectMapper json) {
        this.targets = targets;
        this.json = json;
    }

    // -------------------------------------------------- queries

    public SalesOrderView getSalesOrder(UUID id, String token) {
        return getOne("sales", "/api/sales-orders/" + id, token, SalesOrderView.class);
    }

    public List<SalesOrder360View> listSalesOrders(String token) {
        return getList("reporting", "/api/sales-orders", token, SalesOrder360View.class);
    }

    public ProductView getProduct(UUID id, String token) {
        return getOne("product", "/api/products/" + id, token, ProductView.class);
    }

    public List<ProductView> listProducts(String token) {
        return getList("product", "/api/products", token, ProductView.class);
    }

    public CustomerView getCustomer(UUID id, String token) {
        return getOne("sales", "/api/customers/" + id, token, CustomerView.class);
    }

    public List<CustomerView> listCustomers(String token) {
        return getList("sales", "/api/customers", token, CustomerView.class);
    }

    public List<StockItemView> listStockItems(String token) {
        return getList("inventory", "/api/stock-items", token, StockItemView.class);
    }

    public List<AvailableToPromiseView> listAtp(String token) {
        return getList("reporting", "/api/atp", token, AvailableToPromiseView.class);
    }

    // -------------------------------------------------- mutation

    public SalesOrderView placeOrder(PlaceOrderInput input, String token) {
        String path = "/api/sales-orders";
        HttpRequest.Builder builder = newRequest("sales", path, token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)));
        HttpResponse<String> response = send(builder, "POST", "sales", path);
        if (response.statusCode() / 100 != 2) {
            throw new UpstreamException("POST " + path, response.statusCode(), response.body());
        }
        return json.readValue(response.body(), SalesOrderView.class);
    }

    // -------------------------------------------------- helpers

    private <T> T getOne(String service, String path, String token, Class<T> type) {
        HttpResponse<String> response = send(newRequest(service, path, token).GET(), "GET", service, path);
        if (response.statusCode() == 404) return null;
        if (response.statusCode() / 100 != 2) {
            throw new UpstreamException("GET " + path, response.statusCode(), response.body());
        }
        return json.readValue(response.body(), type);
    }

    private <T> List<T> getList(String service, String path, String token, Class<T> type) {
        HttpResponse<String> response = send(newRequest(service, path, token).GET(), "GET", service, path);
        if (response.statusCode() / 100 != 2) {
            throw new UpstreamException("GET " + path, response.statusCode(), response.body());
        }
        // Read into T[] then wrap — avoids a TypeFactory/parameterised-type
        // dance for the bare List<T> the services return.
        @SuppressWarnings("unchecked")
        Class<T[]> arrayType = (Class<T[]>) Array.newInstance(type, 0).getClass();
        T[] array = json.readValue(response.body(), arrayType);
        return Arrays.asList(array);
    }

    private HttpRequest.Builder newRequest(String service, String path, String token) {
        URI uri = URI.create(targets.forName(service) + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(30));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder, String method, String service, String path) {
        log.info("→ {} {}{}", method, service, path);
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UpstreamException(method + " " + service + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(method + " " + service + path + " interrupted", e);
        }
    }
}
