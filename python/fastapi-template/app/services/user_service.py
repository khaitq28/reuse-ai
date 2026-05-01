import uuid

import structlog
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.security import hash_password
from app.exceptions.domain import EmailAlreadyExistsError, UserNotFoundError
from app.models.user import User
from app.repositories.user_repository import UserRepository
from app.schemas.user import UserCreate, UserUpdate

logger = structlog.get_logger(__name__)


class UserService:
    def __init__(self, session: AsyncSession) -> None:
        self.repo = UserRepository(session)

    async def create_user(self, data: UserCreate) -> User:
        if await self.repo.email_exists(data.email):
            raise EmailAlreadyExistsError(data.email)

        user = User(
            email=data.email,
            hashed_password=hash_password(data.password),
            full_name=data.full_name,
            role=data.role,
        )
        user = await self.repo.create(user)
        logger.info("user_created", user_id=str(user.id), email=user.email)
        return user

    async def get_user(self, user_id: uuid.UUID) -> User:
        user = await self.repo.get(user_id)
        if user is None:
            raise UserNotFoundError(str(user_id))
        return user

    async def list_users(
        self, skip: int = 0, limit: int = 20
    ) -> tuple[list[User], int]:
        return await self.repo.list(skip=skip, limit=limit)

    async def update_user(self, user_id: uuid.UUID, data: UserUpdate) -> User:
        user = await self.get_user(user_id)
        update_data = data.model_dump(exclude_none=True)
        return await self.repo.update(user, update_data)

    async def delete_user(self, user_id: uuid.UUID) -> None:
        await self.repo.soft_delete(user_id)
        logger.info("user_deleted", user_id=str(user_id))
