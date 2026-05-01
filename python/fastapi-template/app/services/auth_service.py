import structlog
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import (
    create_access_token,
    create_refresh_token,
    decode_token,
    verify_password,
)
from app.exceptions.domain import InvalidCredentialsError, UnauthorizedError
from app.repositories.user_repository import UserRepository
from app.schemas.auth import LoginRequest, TokenResponse

logger = structlog.get_logger(__name__)


class AuthService:
    def __init__(self, session: AsyncSession) -> None:
        self.repo = UserRepository(session)

    async def login(self, data: LoginRequest) -> TokenResponse:
        user = await self.repo.find_by_email(data.email)
        if user is None or not verify_password(data.password, user.hashed_password):
            raise InvalidCredentialsError()

        if not user.is_active:
            raise UnauthorizedError("Account is deactivated")

        access_token = create_access_token(subject=str(user.id))
        refresh_token = create_refresh_token(subject=str(user.id))

        logger.info("user_login", user_id=str(user.id), email=user.email)
        return TokenResponse(access_token=access_token, refresh_token=refresh_token)

    async def refresh(self, refresh_token: str) -> TokenResponse:
        payload = decode_token(refresh_token)

        if payload.get("type") != "refresh":
            raise UnauthorizedError("Invalid token type")

        user_id = payload.get("sub")
        user = await self.repo.get(user_id)  # type: ignore[arg-type]
        if user is None or not user.is_active:
            raise UnauthorizedError("User not found or inactive")

        new_access = create_access_token(subject=user_id)
        new_refresh = create_refresh_token(subject=user_id)

        return TokenResponse(access_token=new_access, refresh_token=new_refresh)
