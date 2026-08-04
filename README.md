# Low-Ops Spring Boot Default Template

<p align="left">
  <img src="./images/logo.svg" height="50" width="60" alt="Low-Ops logo" style="background: white; padding: 20px; border-radius: 10px; margin-right: 20px; box-shadow: 0 4px 8px rgba(0,0,0,0.1)"/>
  <img src="./images/springboot-logo.svg" height="50" width="60" alt="Spring Boot logo" style="background: white; padding: 20px; border-radius: 10px; box-shadow: 0 4px 8px rgba(0,0,0,0.1)"/>
</p>

People desk starter: Spring Boot, PostgreSQL, and S3-compatible storage.

## Local development

```bash
cp .env.example .env
mvn spring-boot:run
```

## Docker

```bash
docker compose up --build
```

- App: `PORT` (default `8000`), health `GET /ready`
- Metrics: `METRICS_PORT` (default `8001`) Prometheus `/metrics`
- OpenAPI: `/api/schema`, Swagger UI `/api/docs`
- Compose includes PostgreSQL and MinIO
- OTEL traces via `OTEL_EXPORTER_OTLP_ENDPOINT` and `OTEL_SERVICE_NAME` (Spring Boot native starter)
