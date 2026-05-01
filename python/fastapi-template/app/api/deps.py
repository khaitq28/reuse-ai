"""
Dependency injection hub.
Spring equivalent: @Autowired / SecurityContextHolder.
All shared dependencies used across routes live here.
"""
import uuid
from collections.abc import AsyncGenerator
from typing import Annotated

import structlog
from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.constants import Role
from app.core.security import decode_token
from app.db.session import get_session
from app.exceptions.domain import ForbiddenError, UnauthorizedError, UserNotFoundError
from app.models.user import User
from app.repositories.user_repository import UserRepository

logger = structlog.get_logger(__name__)

bearer_scheme = HTTPBearer()

DBSession = Annotated[AsyncSession, Depends(get_session)]


async def get_current_user(
    credentials: Annotated[HTTPAuthorizationCredentials, Depends(bearer_scheme)],
    db: DBSession,
) -> User:
    token = credentials.credentials
    payload = decode_token(token)
    if payload.get("type") != "access":
        raise UnauthorizedError("Invalid token type")

    user_id_str = payload.get("sub")
    if not user_id_str:
        raise UnauthorizedError("Token missing subject")

    try:
        user_id = uuid.UUID(user_id_str)
    except ValueError as exc:
        raise UnauthorizedError("Invalid token subject") from exc

    repo = UserRepository(db)
    user = await repo.get(user_id)
    if user is None:
        raise UserNotFoundError(user_id_str)
    if not user.is_active:
        raise UnauthorizedError("Account is deactivated")

    # Bind user_id to structlog context for all logs in this request
    structlog.contextvars.bind_contextvars(user_id=str(user.id))
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]


def require_roles(*roles: Role):
    """
    Factory returning a dependency that enforces role access.
    Spring equivalent: @PreAuthorize("hasRole('ADMIN')")

    Usage:
        @router.get("/admin", dependencies=[Depends(require_roles(Role.ADMIN))])
    """
    async def _check(current_user: CurrentUser) -> User:
        if current_user.role not in [r.value for r in roles]:
            raise ForbiddenError(
                f"Required roles: {[r.value for r in roles]}, got: {current_user.role}"
            )
        return current_user

    return _check
