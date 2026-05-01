# ── Stage 1: Builder ──────────────────────────────────────────────────────────
FROM python:3.12-slim AS builder

WORKDIR /app

# Install uv (fastest Python package manager)
RUN pip install --no-cache-dir uv

# Copy dependency files first (better layer caching)
COPY pyproject.toml ./

# Install production dependencies only, into a virtual environment
RUN uv venv .venv && \
    uv pip install --python .venv/bin/python \
    fastapi "uvicorn[standard]" python-multipart \
    "sqlalchemy[asyncio]" asyncpg alembic \
    "pydantic[email]>=2.7.0" pydantic-settings \
    "python-jose[cryptography]" "passlib[bcrypt]" \
    structlog httpx "redis[asyncio]" slowapi asgi-correlation-id

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM python:3.12-slim AS runtime

# Non-root user — required for K8s securityContext.runAsNonRoot: true
RUN groupadd -r appuser && useradd -r -g appuser -u 1001 appuser

WORKDIR /app

# Copy only the virtual environment from builder (no build tools in final image)
COPY --from=builder /app/.venv ./.venv

# Copy application code
COPY app/ ./app/
COPY alembic/ ./alembic/
COPY alembic.ini ./
COPY scripts/ ./scripts/
COPY .docker/entrypoint.sh ./entrypoint.sh

RUN chmod +x ./entrypoint.sh

# Set PATH to use venv
ENV PATH="/app/.venv/bin:$PATH"
ENV PYTHONPATH="/app"
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

EXPOSE 8000

USER appuser

ENTRYPOINT ["./entrypoint.sh"]
