from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import settings

engine = create_async_engine(
    settings.DATABASE_URL,
    pool_size=10,
    max_overflow=20,
    pool_pre_ping=True,       # Equivalent to Spring's testOnBorrow — validates connection before use
    pool_recycle=3600,        # Recycle connections every hour
    echo=settings.DEBUG,      # Log SQL in debug mode
)

AsyncSessionLocal = async_sessionmaker(
    bind=engine,
    class_=AsyncSession,
    expire_on_commit=False,   # Keep objects usable after commit
    autocommit=False,
    autoflush=False,
)


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    """
    Yields an async DB session with automatic commit on success and rollback on error.
    Spring equivalent: @Transactional on a service method.
    Used as a FastAPI dependency via Depends(get_session).
    """
    async with AsyncSessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
