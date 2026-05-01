"""
CLI script to create an admin user.
Usage: python scripts/create_superuser.py
"""
import asyncio
import sys

sys.path.insert(0, ".")

from app.core.security import hash_password
from app.db.session import AsyncSessionLocal
from app.models.user import User
from app.core.constants import Role


async def main() -> None:
    email = input("Email: ").strip()
    full_name = input("Full name: ").strip()
    password = input("Password: ").strip()

    async with AsyncSessionLocal() as session:
        user = User(
            email=email,
            hashed_password=hash_password(password),
            full_name=full_name,
            role=Role.ADMIN,
        )
        session.add(user)
        await session.commit()
        await session.refresh(user)
        print(f"Admin user created: {user.id} — {user.email}")


if __name__ == "__main__":
    asyncio.run(main())
