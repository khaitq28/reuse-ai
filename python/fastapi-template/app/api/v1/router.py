from fastapi import APIRouter

from app.api.v1.routes import auth, google_auth, health, users

router = APIRouter(prefix="/api/v1")

router.include_router(health.router)
router.include_router(auth.router)
router.include_router(google_auth.router)
router.include_router(users.router)
