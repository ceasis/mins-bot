---
name: api-endpoint-scaffold
description: Add a new REST endpoint stub to an existing project, matching the project's framework and conventions (Spring controller, Express route, FastAPI router). Trigger on "add api endpoint", "scaffold a new rest route", "create GET /api/x", "new controller method".
metadata:
  minsbot:
    emoji: "🛤️"
    os: ["windows", "darwin", "linux"]
---

# API Endpoint Scaffold

## Steps

1. Detect framework from marker files:
   - `pom.xml` / `build.gradle` with Spring → Spring Boot controller
   - `package.json` with `express` → Express route
   - `pyproject.toml` with `fastapi` → FastAPI router
   - `Cargo.toml` with `axum` or `actix-web` → Rust handler
2. Ask the user for:
   - HTTP verb + path (e.g. `GET /api/users/{id}`)
   - Request body shape (for POST/PUT)
   - Response body shape
3. Sample 1–2 existing endpoints from the project to match:
   - Controller vs router vs handler file location
   - Validation approach (annotations, pydantic, zod)
   - Error return format
4. Generate the endpoint code. Match imports + style of sampled files.
5. Add a matching test stub if the project has a test convention for endpoints.
6. Print file path(s) and the command to hit the new endpoint locally (curl).

## Guardrails

- Never modify routing config unless necessary — add the handler to a file that's already being picked up.
- Stub the handler body with `TODO` + a clear return — don't write fake business logic.
- If the project uses OpenAPI / Swagger, also add the annotation/decorator so docs pick it up.
