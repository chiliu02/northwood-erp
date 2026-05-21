---
name: rest-api-conventions
description: >
  Northwood's REST controller, DTO, and error-response conventions. Use when
  adding or editing @RestController classes, request/response DTOs, or endpoints
  in any service's api/ package. Covers the api → application-only rule, the
  /api/{resource} URL scheme + command sub-paths, View/Command vs api/dto
  request records, the shared typed ErrorResponse + DomainExceptionAdvice (no
  per-controller @ExceptionHandler), security meta-annotations, and springdoc.
---

# REST API Conventions (Northwood)

How controllers are written in this codebase. These follow from the hexagonal
layering rule — full rationale + the machine-checkable import bans live in
`CLAUDE.md` (§ *Controllers (api/) must depend only on application/*) and
`docs/conventions.md` (View / Command / Request patterns, exception wrapping).
This skill is the working procedure; read those for the *why*.

## The load-bearing rule

**`api/` may import from `application/` only.** A controller must never import
`domain.*` or `infrastructure.*`. Concretely, a controller injects/uses:

- application services (`ProductService`) and `*QueryPort` / `*Lookup` interfaces;
- `application/dto/*View` and `*Command` records (the wire format);
- `api/dto/*Request` records (only where the request shape diverges).

It must **not** touch domain aggregates/VOs, `*Repository`, `JdbcTemplate`, or
domain exceptions. Services take raw `UUID` and wrap identity internally.

## URL structure

`/api/{resource-plural}` — lowercase, hyphenated, **no** version or schema
segment. Domain commands are sub-paths under the id:

```
GET   /api/products              # list (returns List<ProductView>)
GET   /api/products/{id}         # one
POST  /api/products              # create
PUT   /api/products/{id}/sales-price      # a domain command, not a CRUD field PUT
POST  /api/products/{id}/discontinue      # state-changing command
```

Endpoints mirror domain operations (`/discontinue`, `/sales-price`), not table
columns. No `Pageable`/`Page` — list endpoints return `List<*View>`.

## Controller template

Constructor injection of `final` deps (no Lombok, no `@Autowired`). `UUID` path
vars passed straight to the service. Return a `*View` (200/201) or `Void` (204).
Guard mutations with the security meta-annotations from `shared.api.security`.

```java
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    @RequireCatalogManager
    public ResponseEntity<ProductView> create(@Valid @RequestBody CreateProductRequest request) {
        ProductView view = service.createProduct(request.sku(), request.name(), /* … */);
        return ResponseEntity.created(URI.create("/api/products/" + view.productId())).body(view);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductView> getById(@PathVariable UUID id) {
        return service.findById(id)               // service returns Optional<ProductView>
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}/sales-price")
    @RequireCatalogManager
    public ResponseEntity<Void> changeSalesPrice(
        @PathVariable UUID id,
        @Valid @RequestBody ChangeSalesPriceRequest request
    ) {
        service.changeSalesPrice(id, request.salesPrice(), request.currencyCode());
        return ResponseEntity.noContent().build();
    }
}
```

## DTOs

- **Response:** `application/dto/*View` records — returned directly as the wire
  format. No `api/dto/*Response` mirror for a 1:1 shape (YAGNI).
- **Request:** `api/dto/*Request` records carrying Bean Validation constraints;
  validate with `@Valid @RequestBody`. The controller unpacks them into service
  arguments (primitives) or an `application/dto/*Command`.
- Never serialize a domain aggregate or VO.

## Error responses — do NOT write `@ExceptionHandler` in controllers

Error handling is centralised in the shared `DomainExceptionAdvice`
(`@RestControllerAdvice`, auto-wired into every service). Controllers never
catch-and-translate.

The pattern: the **service** throws an application-layer `DomainException`
subclass; the advice maps it to a status + a typed `ErrorResponse`:

| Throw (application layer) | HTTP | Body |
|---|---|---|
| a `NotFoundException` subclass | 404 | `ErrorResponse{code, params}` |
| a `ConflictException` subclass | 409 | `ErrorResponse{code, params}` |
| a `BadRequestException` subclass | 400 | `ErrorResponse{code, params}` |

`ErrorResponse` is `record(String code, Map<String,Object> params)` — a stable
**code** the SPA looks up in its message catalogue for i18n (not an English
string, not RFC 7807 ProblemDetail). Define a concrete exception with its own
`CODE` rather than throwing raw `IllegalArgumentException`/`IllegalStateException`
— those hit fallback handlers that emit a generic `GENERIC_*_VIOLATION` code and
log a warning to promote them.

For a simple "not found" on a GET, returning `Optional` → `notFound()` (as in
`getById` above) is also fine; mutations rely on the service throwing.

## OpenAPI / Swagger

springdoc-openapi: spec at `/v3/api-docs`, UI at `/swagger-ui/index.html` per
service (e.g. product on `:8081`). Add `@Tag`/`@Operation` only where the extra
prose earns its keep — the method + DTO signatures already document most shapes.

## Checklist for a new endpoint

1. Request shape → `api/dto/*Request` record (+ Bean Validation) if it diverges from a `*Command`.
2. Controller method in `api/`: `UUID` path vars, `@Valid @RequestBody`, delegate to the application service, return `*View` or `Void`.
3. Mutation? Add the right `shared.api.security` meta-annotation (`@RequireCatalogManager`, …).
4. New failure mode? Add a typed `NotFoundException`/`ConflictException`/`BadRequestException` subclass with a `CODE` in the application layer — never a per-controller `@ExceptionHandler`.
5. Confirm zero `domain.*` / `infrastructure.*` imports in the controller (the CLAUDE.md grep rule).
