"""
Generic async repository.
Spring equivalent: JpaRepository<T, ID> — get, list, create, update, delete.
"""
import uuid
from typing import Any, Generic, TypeVar

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.base import Base
from app.exceptions.domain import NotFoundError

ModelType = TypeVar("ModelType", bound=Base)


class BaseRepository(Generic[ModelType]):
    def __init__(self, model: type[ModelType], session: AsyncSession) -> None:
        self.model = model
        self.session = session

    async def get(self, id: uuid.UUID) -> ModelType | None:
        result = await self.session.execute(
            select(self.model).where(
                self.model.id == id,  # type: ignore[attr-defined]
                self.model.is_deleted == False,  # noqa: E712
            )
        )
        return result.scalar_one_or_none()

    async def get_or_raise(self, id: uuid.UUID) -> ModelType:
        obj = await self.get(id)
        if obj is None:
            raise NotFoundError(f"{self.model.__name__} with id '{id}' not found")
        return obj

    async def list(
        self,
        skip: int = 0,
        limit: int = 20,
        filters: dict[str, Any] | None = None,
    ) -> tuple[list[ModelType], int]:
        query = select(self.model).where(
            self.model.is_deleted == False  # noqa: E712
        )
        count_query = select(func.count()).select_from(self.model).where(
            self.model.is_deleted == False  # noqa: E712
        )

        if filters:
            for key, value in filters.items():
                query = query.where(getattr(self.model, key) == value)
                count_query = count_query.where(getattr(self.model, key) == value)

        total_result = await self.session.execute(count_query)
        total = total_result.scalar_one()

        result = await self.session.execute(query.offset(skip).limit(limit))
        items = list(result.scalars().all())

        return items, total

    async def create(self, obj: ModelType) -> ModelType:
        self.session.add(obj)
        await self.session.flush()  # Flush to get DB-generated values (id, timestamps)
        await self.session.refresh(obj)
        return obj

    async def update(self, obj: ModelType, data: dict[str, Any]) -> ModelType:
        for key, value in data.items():
            if value is not None:
                setattr(obj, key, value)
        await self.session.flush()
        await self.session.refresh(obj)
        return obj

    async def soft_delete(self, id: uuid.UUID) -> None:
        obj = await self.get_or_raise(id)
        obj.is_deleted = True  # type: ignore[attr-defined]
        await self.session.flush()
