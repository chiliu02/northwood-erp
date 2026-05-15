---
name: rest-api-conventions
description: >
  REST controller, DTO, and exception handling conventions for the AdventureWorks
  Spring Boot API. Use when creating or editing @RestController classes, DTOs,
  GlobalExceptionHandler, or when defining new REST endpoints. Also applies when
  the user asks about API structure, pagination, error responses, or OpenAPI docs.
allowed-tools: Read, Grep, Glob
---

# REST API Conventions

## URL Structure

```
/api/v1/{schema}/{resource-plural}
/api/v1/{schema}/{resource-plural}/{id}
```

Examples:
```
GET  /api/v1/person/persons?page=0&size=20
GET  /api/v1/person/persons/42
POST /api/v1/person/persons
PUT  /api/v1/person/persons/42
GET  /api/v1/sales/sales-orders?page=0&size=20&sort=orderDate,desc
GET  /api/v1/production/products?category=Bikes
```

## Controller Template

```java
@RestController
@RequestMapping("/api/v1/person/persons")
@RequiredArgsConstructor
@Tag(name = "Person", description = "Person management APIs")
public class PersonController {

    private final PersonService personService;

    @GetMapping
    public Page<PersonDto> list(Pageable pageable) {
        return personService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public PersonDto get(@PathVariable Integer id) {
        return personService.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PersonDto create(@RequestBody @Valid CreatePersonRequest request) {
        return personService.create(request);
    }

    @PutMapping("/{id}")
    public PersonDto update(@PathVariable Integer id,
                            @RequestBody @Valid UpdatePersonRequest request) {
        return personService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Integer id) {
        personService.delete(id);
    }
}
```

## DTO Conventions

Use Java Records for response DTOs:
```java
public record PersonDto(
    Integer id,
    String personType,
    String firstName,
    String middleName,
    String lastName,
    String emailPromotion
) {}
```

Use classes with `@NotNull`, `@Size` etc. for request DTOs:
```java
public class CreatePersonRequest {
    @NotNull @Size(max = 2)
    private String personType;

    @NotNull @Size(max = 50)
    private String firstName;

    @NotNull @Size(max = 50)
    private String lastName;
}
```

## Exception Handling

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(EntityNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        // collect field errors
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        pd.setDetail("Validation failed");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage()).toList());
        return pd;
    }
}
```

## Pagination

All list endpoints MUST accept `Pageable`:
```java
// Spring auto-maps ?page=0&size=20&sort=lastName,asc
public Page<PersonDto> list(Pageable pageable) { ... }
```

## OpenAPI / Swagger

Annotate controllers with `@Tag` and methods with `@Operation`:
```java
@Operation(summary = "Get person by ID", description = "Returns a single person by their BusinessEntityID")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "Found"),
    @ApiResponse(responseCode = "404", description = "Not found")
})
```

Swagger UI available at: `http://localhost:8080/swagger-ui.html`

## Lookup Value Endpoints (Read-Only)

For Lookup Values (e.g. AddressType, ShipMethod), only expose GET endpoints:
```java
@RestController
@RequestMapping("/api/v1/person/address-types")
public class AddressTypeController {
    @GetMapping public List<AddressTypeDto> list() { ... }
    @GetMapping("/{id}") public AddressTypeDto get(@PathVariable Integer id) { ... }
}
```
