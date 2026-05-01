import uuid
import math

from fastapi import APIRouter, Depends, Query, status

from app.api.deps import CurrentUser, DBSession, require_roles
from app.core.constants import Role
from app.schemas.common import PaginatedResponse
from app.schemas.user import UserCreate, UserListResponse, UserResponse, UserUpdate
from app.services.user_service import UserService

router = APIRouter(prefix="/users", tags=["users"])


@router.post("", response_model=UserResponse, status_code= status.HTTP_201_CREATED,
             dependencies=[Depends(require_roles(Role.ADMIN))])
async def create_user(data: UserCreate, db: DBSession) -> UserResponse:
    user = await UserService(db).create_user(data)
    return UserResponse.model_validate(user)


@router.get("", response_model=PaginatedResponse[UserListResponse],
            dependencies=[Depends(require_roles(Role.ADMIN))])
async def list_users(
    db: DBSession,
    page: int = Query(1, ge=1),
    page_size: int = Query(20, ge=1, le=100),
) -> PaginatedResponse[UserListResponse]:
    skip = (page - 1) * page_size
    users, total = await UserService(db).list_users(skip=skip, limit=page_size)
    return PaginatedResponse(
        items=[UserListResponse.model_validate(u) for u in users],
        total=total,
        page=page,
        page_size=page_size,
        pages=math.ceil(total / page_size),
    )


@router.get("/me", response_model=UserResponse)
async def get_me(current_user: CurrentUser) -> UserResponse:
    return UserResponse.model_validate(current_user)


@router.get("/{user_id}", response_model=UserResponse,
            dependencies=[Depends(require_roles(Role.ADMIN))])
async def get_user(user_id: uuid.UUID, db: DBSession) -> UserResponse:
    user = await UserService(db).get_user(user_id)
    return UserResponse.model_validate(user)


@router.patch("/{user_id}", response_model=UserResponse,
              dependencies=[Depends(require_roles(Role.ADMIN))])
async def update_user(user_id: uuid.UUID, data: UserUpdate, db: DBSession) -> UserResponse:
    user = await UserService(db).update_user(user_id, data)
    return UserResponse.model_validate(user)


@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT,
               dependencies=[Depends(require_roles(Role.ADMIN))])
async def delete_user(user_id: uuid.UUID, db: DBSession) -> None:
    await UserService(db).delete_user(user_id)
