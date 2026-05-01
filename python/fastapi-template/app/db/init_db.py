import structlog
from sqlalchemy import text, select

from app.db.session import engine, AsyncSessionLocal

logger = structlog.get_logger(__name__)


async def check_db_connection() -> None:
    """Verify DB is reachable at startup. Fail fast before accepting traffic."""
    async with engine.begin() as conn:
        await conn.execute(text("SELECT 1"))
    logger.info("database_connection_ok")


async def create_tables() -> None:
    """Create all tables from models if they don't exist yet."""
    from app.db.base import Base
    import app.models.user  # noqa: F401 — must import all models for Base to know them

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    logger.info("database_tables_ready")


async def seed_admin_user() -> None:
    """Create default admin user (admin@admin.com/admin) if no admin exists yet."""
    from app.models.user import User
    from app.core.constants import Role
    from app.core.security import hash_password

    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(User).where(User.email == "admin@admin.com", User.is_deleted.is_(False))
        )
        if result.scalar_one_or_none() is not None:
            return

        admin = User(
            email="admin@admin.com",
            hashed_password=hash_password("admin"),
            full_name="Admin",
            role=Role.ADMIN,
        )
        session.add(admin)
        await session.commit()
        logger.info("default_admin_created", email="admin@admin.com")
