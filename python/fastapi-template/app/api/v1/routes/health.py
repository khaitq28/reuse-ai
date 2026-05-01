from fastapi import APIRouter
from sqlalchemy import text
from starlette.responses import JSONResponse

from app.core.config import settings
from app.db.session import engine

router = APIRouter(tags=["health"])


@router.get("/health")
async def health() -> dict:
    """Basic health — returns app metadata. Always 200."""
    return {
        "status": "ok",
        "app": settings.APP_NAME,
        "version": settings.APP_VERSION,
        "environment": settings.ENVIRONMENT,
    }


@router.get("/health/live")
async def liveness() -> dict:
    """
    Liveness probe — is the process alive?
    K8s kills and restarts the pod if this fails.
    Never check external dependencies here.
    """
    return {"status": "ok"}


@router.get("/health/ready")
async def readiness() -> JSONResponse:
    """
    Readiness probe — can this pod serve traffic?
    K8s removes the pod from load balancer rotation if this returns non-2xx.
    Check DB connectivity here.
    """
    checks: dict[str, str] = {}
    healthy = True

    try:
        async with engine.begin() as conn:
            await conn.execute(text("SELECT 1"))
        checks["database"] = "ok"
    except Exception as e:
        checks["database"] = f"error: {e}"
        healthy = False

    status_code = 200 if healthy else 503
    return JSONResponse(
        status_code=status_code,
        content={"status": "ok" if healthy else "degraded", "checks": checks},
    )
