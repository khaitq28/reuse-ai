"""
Google OAuth2 login flow.

GET  /auth/google          → returns the Google authorization URL
GET  /auth/google/callback → exchanges code for tokens, returns JWT
"""
import httpx
from fastapi import APIRouter, Depends

from app.api.deps import DBSession
from app.core.config import settings
from app.core.constants import Role
from app.core.security import create_access_token, create_refresh_token
from app.exceptions.domain import UnauthorizedError
from app.models.user import User
from app.repositories.user_repository import UserRepository
from app.schemas.auth import TokenResponse

router = APIRouter(prefix="/auth/google", tags=["auth"])

GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
GOOGLE_USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"


@router.get("", summary="Get Google login URL")
async def google_login() -> dict[str, str]:
    params = {
        "client_id": settings.GOOGLE_CLIENT_ID,
        "redirect_uri": settings.GOOGLE_REDIRECT_URI,
        "response_type": "code",
        "scope": "openid email profile",
        "access_type": "offline",
    }
    query = "&".join(f"{k}={v}" for k, v in params.items())
    url = f"{GOOGLE_AUTH_URL}?{query}"
    return {"url": url}


@router.get("/callback", response_model=TokenResponse, summary="Google OAuth2 callback")
async def google_callback(code: str, db: DBSession) -> TokenResponse:
    # Exchange authorization code for Google tokens
    async with httpx.AsyncClient() as client:
        token_response = await client.post(
            GOOGLE_TOKEN_URL,
            data={
                "code": code,
                "client_id": settings.GOOGLE_CLIENT_ID,
                "client_secret": settings.GOOGLE_CLIENT_SECRET,
                "redirect_uri": settings.GOOGLE_REDIRECT_URI,
                "grant_type": "authorization_code",
            },
        )

    if token_response.status_code != 200:
        raise UnauthorizedError("Failed to exchange code with Google")

    google_access_token = token_response.json().get("access_token")

    # Fetch user profile from Google
    async with httpx.AsyncClient() as client:
        userinfo_response = await client.get(
            GOOGLE_USERINFO_URL,
            headers={"Authorization": f"Bearer {google_access_token}"},
        )

    if userinfo_response.status_code != 200:
        raise UnauthorizedError("Failed to fetch user info from Google")

    google_user = userinfo_response.json()
    google_id = google_user.get("id")
    email = google_user.get("email")
    full_name = google_user.get("name", email)

    if not google_id or not email:
        raise UnauthorizedError("Google did not return required user info")

    # Find or create user
    repo = UserRepository(db)
    user = await repo.find_by_google_id(google_id)

    if user is None:
        # Check if account with same email already exists → link it
        user = await repo.find_by_email(email)
        if user is not None:
            user.google_id = google_id
        else:
            # New user — create account
            user = User(
                email=email,
                full_name=full_name,
                hashed_password=None,
                role=Role.USER,
                google_id=google_id,
            )
            db.add(user)

        await db.flush()
        await db.refresh(user)

    return TokenResponse(
        access_token=create_access_token(str(user.id)),
        refresh_token=create_refresh_token(str(user.id)),
    )
