.PHONY: run run-local test test-cov lint format typecheck migrate migration build clean superuser

# ── Local development ──────────────────────────────────────────────────────────

run:
	docker compose up --build

run-local:
	uvicorn app.main:app --reload --port 8000

# ── Testing ────────────────────────────────────────────────────────────────────

test:
	pytest -v --tb=short

test-cov:
	pytest --cov=app --cov-report=html
	@echo "Coverage report: htmlcov/index.html"

# ── Code quality ───────────────────────────────────────────────────────────────

lint:
	ruff check app tests

format:
	ruff format app tests

typecheck:
	mypy app

# ── Database migrations ────────────────────────────────────────────────────────

migrate:
	alembic upgrade head

# Usage: make migration MSG="add users table"
migration:
	alembic revision --autogenerate -m "$(MSG)"

migrate-down:
	alembic downgrade -1

# ── Docker ─────────────────────────────────────────────────────────────────────

build:
	docker build -f .docker/Dockerfile --target runtime -t fastapi-template:local .

# ── Utilities ──────────────────────────────────────────────────────────────────

clean:
	find . -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true
	find . -type f -name "*.pyc" -delete
	rm -f test.db

superuser:
	python scripts/create_superuser.py
