"""
Application factory — Spring Boot's @SpringBootApplication equivalent.
Lifespan manages startup/shutdown. Middleware and routers registered here.
"""
from contextlib import asynccontextmanager
from collections.abc import AsyncGenerator

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.middleware.request_logging import RequestLoggingMiddleware
from app.api.v1.router import router as v1_router
from app.core.config import settings
from app.core.exception_handlers import register_exception_handlers
from app.core.logging import setup_logging
from app.db.init_db import check_db_connection, create_tables, seed_admin_user
from app.db.session import engine


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Startup and shutdown logic. Spring equivalent: ApplicationListener<ContextRefreshedEvent>."""
    await check_db_connection()
    await create_tables()
    await seed_admin_user()
    yield
    await engine.dispose()


def create_app() -> FastAPI:
    app = FastAPI(
        title=settings.APP_NAME,
        version=settings.APP_VERSION,
        docs_url="/docs" if not settings.is_production else None,
        redoc_url="/redoc" if not settings.is_production else None,
        openapi_url="/openapi.json" if not settings.is_production else None,
        lifespan=lifespan,
    )

    # CORS — configure per environment
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.CORS_ORIGINS,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    # Request logging middleware
    app.add_middleware(RequestLoggingMiddleware)

    # Global exception handlers
    register_exception_handlers(app)

    # Routers
    app.include_router(v1_router)

    return app


# Configure logging at module load time (before any logger is used)
setup_logging()
app = create_app()
