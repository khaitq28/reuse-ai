# FastAPI Template — Full Explanation
> For a Senior Java/Spring Boot developer learning Python

---

## Table of Contents

1. [Python basics you must know first](#1-python-basics-you-must-know-first)
2. [Project structure overview](#2-project-structure-overview)
3. [How a request flows through the app](#3-how-a-request-flows-through-the-app)
4. [File-by-file explanation](#4-file-by-file-explanation)
   - [pyproject.toml — the pom.xml](#pyprojecttoml--the-pomxml)
   - [app/main.py — the @SpringBootApplication](#appmainpy--the-springbootapplication)
   - [app/core/config.py — application.yml](#appcoreconfpy--applicationyml)
   - [app/core/constants.py — enums](#appcoreconstantspy--enums)
   - [app/core/security.py — JWT + password hashing](#appcoresecuritypy--jwt--password-hashing)
   - [app/core/logging.py — Logback equivalent](#appcoreloggingpy--logback-equivalent)
   - [app/core/exception_handlers.py — @ControllerAdvice](#appcoreexception_handlerspy--controlleradvice)
   - [app/exceptions/domain.py — custom exceptions](#appexceptionsdomainpy--custom-exceptions)
   - [app/db/base.py — JPA MetaData](#appdbbasepy--jpa-metadata)
   - [app/db/session.py — DataSource + @Transactional](#appdbsessionpy--datasource--transactional)
   - [app/db/init_db.py — startup DB check](#appdbinitdbpy--startup-db-check)
   - [app/models/base_model.py — @MappedSuperclass](#appmodelsbase_modelpy--mappedsuperclass)
   - [app/models/user.py — @Entity](#appmodelsuserpy--entity)
   - [app/schemas/common.py — generic DTOs](#appschemacommonpy--generic-dtos)
   - [app/schemas/user.py — request/response DTOs](#appschemauserpy--requestresponse-dtos)
   - [app/schemas/auth.py — auth DTOs](#appschemaauthorpy--auth-dtos)
   - [app/repositories/base_repository.py — JpaRepository](#apprepositoriesbase_repositorypy--jparepository)
   - [app/repositories/user_repository.py — custom repository](#apprepositoriesuser_repositorypy--custom-repository)
   - [app/services/user_service.py — @Service](#appservicesuser_servicepy--service)
   - [app/services/auth_service.py — @Service](#appservicesauth_servicepy--service)
   - [app/api/deps.py — @Autowired + SecurityContext](#appapidepspy--autowired--securitycontext)
   - [app/api/middleware/request_logging.py — OncePerRequestFilter](#appapimiddlewarerequest_loggingpy--onceperrequestfilter)
   - [app/api/v1/router.py — route aggregator](#appapiv1routerpy--route-aggregator)
   - [app/api/v1/routes/health.py — health endpoints](#appapiv1routeshealthpy--health-endpoints)
   - [app/api/v1/routes/auth.py — auth endpoints](#appapiv1routesauthpy--auth-endpoints)
   - [app/api/v1/routes/users.py — @RestController](#appapiv1routesuserspy--restcontroller)
   - [alembic/ — Flyway/Liquibase equivalent](#alembic--flywayliquibase-equivalent)
   - [.docker/Dockerfile — multi-stage build](#dockerdockerfile--multi-stage-build)
   - [docker-compose.yml — local stack](#docker-composeyml--local-stack)
   - [Makefile — Maven wrapper equivalent](#makefile--maven-wrapper-equivalent)
5. [Key Python concepts explained for Java devs](#5-key-python-concepts-explained-for-java-devs)
6. [How to add a new feature (step-by-step)](#6-how-to-add-a-new-feature-step-by-step)

---

## 1. Python Basics You Must Know First

### No compilation — Python runs directly

```python
# Java: you write code → compile → run .class file
# Python: you write code → run it directly
uvicorn app.main:app   # "run the 'app' object in 'app/main.py'"
```

### `async` / `await` — non-blocking I/O

Python has the same concept as Java's `CompletableFuture`, but cleaner syntax.

```python
# Java equivalent:
# CompletableFuture<User> user = userRepository.findById(id);

# Python:
user = await repo.get(id)   # await = .get() on a Future
```

Every function that talks to DB or network must be `async def` and called with `await`.
If you forget `await`, you get a coroutine object, not the result. This is the #1 Python async mistake.

### `def` vs `async def`

```python
def normal_function():       # synchronous — blocks the thread
    return "hello"

async def async_function():  # asynchronous — yields control while waiting
    result = await db_call()
    return result
```

### Type hints — optional but used everywhere here

```python
# Java: String name = "Khai";
# Python without hints: name = "Khai"
# Python with hints (what we use):
name: str = "Khai"
def greet(name: str) -> str:
    return f"Hello {name}"
```

Type hints in Python are **not enforced at runtime** (unlike Java). They're for IDE autocomplete and static checkers like `mypy`.

### `self` — like `this` in Java

```python
class UserService:
    def __init__(self, session):   # constructor
        self.repo = UserRepository(session)   # this.repo = ...

    async def get_user(self, user_id):   # every method takes self as first param
        return await self.repo.get(user_id)
```

### Inheritance

```python
# Java: class UserRepository extends JpaRepository<User, UUID>
# Python:
class UserRepository(BaseRepository[User]):
    pass
```

### `__init__.py` — package marker

Every folder that contains Python code needs an empty `__init__.py` file.
It's like telling Python "this folder is a package (module)".
You never need to write anything in them — just create them.

### Imports

```python
# Java: import com.example.service.UserService;
# Python:
from app.services.user_service import UserService
# or:
import app.services.user_service
```

Python resolves imports relative to the project root (`PYTHONPATH=/app` in Docker).

---

## 2. Project Structure Overview

```
fastapi-template/
│
├── app/                        ← All application code (like src/main/java/)
│   ├── main.py                 ← @SpringBootApplication — entry point
│   │
│   ├── core/                   ← Cross-cutting concerns
│   │   ├── config.py           ← application.yml (reads .env)
│   │   ├── constants.py        ← Enums (Role, TokenType...)
│   │   ├── security.py         ← JWT encode/decode, password hashing
│   │   ├── logging.py          ← Logback configuration
│   │   └── exception_handlers.py ← @ControllerAdvice
│   │
│   ├── exceptions/
│   │   └── domain.py           ← Custom exceptions (UserNotFoundException...)
│   │
│   ├── db/                     ← Database plumbing
│   │   ├── base.py             ← SQLAlchemy Base (like JPA EntityManager setup)
│   │   ├── session.py          ← Connection pool + session factory
│   │   └── init_db.py          ← Startup DB health check
│   │
│   ├── models/                 ← @Entity classes (ORM)
│   │   ├── base_model.py       ← @MappedSuperclass (id, timestamps, soft-delete)
│   │   └── user.py             ← User entity/table
│   │
│   ├── schemas/                ← DTOs (request/response objects)
│   │   ├── common.py           ← PaginatedResponse, ErrorResponse
│   │   ├── user.py             ← UserCreate, UserUpdate, UserResponse
│   │   └── auth.py             ← LoginRequest, TokenResponse
│   │
│   ├── repositories/           ← Data access layer
│   │   ├── base_repository.py  ← JpaRepository<T, ID> — generic CRUD
│   │   └── user_repository.py  ← UserRepository — custom queries
│   │
│   ├── services/               ← Business logic layer
│   │   ├── user_service.py     ← UserService @Service
│   │   └── auth_service.py     ← AuthService @Service
│   │
│   └── api/                    ← HTTP layer
│       ├── deps.py             ← Dependency injection (get_db, get_current_user)
│       ├── middleware/
│       │   └── request_logging.py ← OncePerRequestFilter (logs every request)
│       └── v1/
│           ├── router.py       ← Aggregates all routes under /api/v1
│           └── routes/
│               ├── health.py   ← GET /health, /health/live, /health/ready
│               ├── auth.py     ← POST /auth/login, /auth/refresh
│               └── users.py    ← CRUD /users — @RestController
│
├── alembic/                    ← Flyway/Liquibase — DB migrations
│   ├── env.py                  ← Migration config (reads DATABASE_URL)
│   └── versions/               ← Migration SQL files (auto-generated)
│
├── tests/                      ← Test code (like src/test/java/)
│   ├── conftest.py             ← @TestConfiguration (shared fixtures)
│   └── integration/api/        ← Integration tests
│
├── scripts/
│   └── create_superuser.py     ← CLI to seed admin user
│
├── .docker/
│   ├── Dockerfile              ← Multi-stage build
│   └── entrypoint.sh           ← Container startup script
│
├── docker-compose.yml          ← Full local stack (app + postgres + redis)
├── pyproject.toml              ← pom.xml equivalent
├── alembic.ini                 ← Alembic config file
├── Makefile                    ← Maven wrapper equivalent
└── .env                        ← Local environment variables (git-ignored)
```

### Spring Boot → FastAPI mapping cheat sheet

| Spring Boot | FastAPI Template |
|---|---|
| `@SpringBootApplication` | `app/main.py` → `create_app()` |
| `application.yml` | `app/core/config.py` + `.env` |
| `@RestController` | `app/api/v1/routes/*.py` with `@router.get/post/...` |
| `@Service` | `app/services/*.py` |
| `@Repository` / `JpaRepository` | `app/repositories/*.py` |
| `@Entity` | `app/models/*.py` (SQLAlchemy ORM) |
| `@Schema` / DTO record | `app/schemas/*.py` (Pydantic) |
| `@ControllerAdvice` | `app/core/exception_handlers.py` |
| `@Transactional` | `get_session()` dependency auto-commits/rollbacks |
| `OncePerRequestFilter` | `app/api/middleware/request_logging.py` |
| `@MappedSuperclass` | `app/models/base_model.py` → `BaseModel` |
| `@PreAuthorize("hasRole(...)")` | `Depends(require_roles(Role.ADMIN))` |
| `SecurityContextHolder` | `CurrentUser` dependency in `deps.py` |
| `Flyway` / `Liquibase` | `alembic` |
| `Logback` / MDC | `structlog` with context vars |
| `mvn package` | `docker build` |
| `mvn spring-boot:run` | `make run` or `uvicorn app.main:app` |
| Bean Validation (`@NotNull`, `@Size`) | Pydantic field validators |
| `ResponseEntity<T>` | `response_model=UserResponse` on route decorator |
| `@Autowired` | `Depends(...)` in function parameters |

---

## 3. How a Request Flows Through the App

Let's trace `POST /api/v1/auth/login` from start to finish.

```
Client
  │
  ▼
Docker port 8000
  │
  ▼
Uvicorn (HTTP server — like embedded Tomcat)
  │
  ▼
FastAPI app (app/main.py)
  │
  ├─► CORSMiddleware        (checks Origin header)
  ├─► RequestLoggingMiddleware  (logs method, path, duration)
  │
  ▼
Router matches /api/v1/auth/login → auth.py route handler
  │
  ▼
FastAPI reads request body, validates it against LoginRequest schema (Pydantic)
  │   → if validation fails: RequestValidationError → 422 response (automatic)
  │
  ▼
FastAPI injects dependencies declared in the function signature:
  └─► db: DBSession → calls get_session() → opens AsyncSession
  │
  ▼
Route handler calls: AuthService(db).login(data)
  │
  ▼
AuthService.login():
  ├─► UserRepository.find_by_email(email)    ← DB query (async)
  ├─► verify_password(plain, hashed)         ← bcrypt check
  ├─► create_access_token(user.id)           ← JWT sign
  ├─► create_refresh_token(user.id)          ← JWT sign
  └─► returns TokenResponse
  │
  ▼
get_session() auto-commits the DB session (no writes here, but pattern is consistent)
  │
  ▼
FastAPI serializes TokenResponse to JSON
  │
  ▼
RequestLoggingMiddleware logs: POST /api/v1/auth/login 200 47ms
  │
  ▼
Response → Client
```

If anything throws an exception at any point:
- `AppException` (or subclass) → `domain_exception_handler` → proper JSON error
- `RequestValidationError` → `validation_exception_handler` → 422
- Any other `Exception` → `unhandled_exception_handler` → 500

---

## 4. File-by-File Explanation

---

### `pyproject.toml` — the pom.xml

```toml
[project]
name = "fastapi-template"
requires-python = ">=3.12"        # like <java.version>17</java.version>

[project.dependencies]
fastapi = ">=0.115.0"             # the web framework
uvicorn = ...                     # HTTP server (like embedded Tomcat)
sqlalchemy = ...                  # ORM (like Hibernate/JPA)
asyncpg = ...                     # async PostgreSQL driver (like JDBC but async)
alembic = ...                     # DB migrations (like Flyway)
pydantic = ...                    # validation / DTOs (like Bean Validation + records)
pydantic-settings = ...           # reads .env into typed settings
python-jose = ...                 # JWT library
passlib = ...                     # password hashing (bcrypt)
structlog = ...                   # structured logging (like Logback with JSON encoder)
```

**`[tool.ruff]`** — code linter + formatter (replaces Checkstyle + SpotBugs + Google Java Format)
**`[tool.mypy]`** — static type checker (like the Java compiler, but optional)
**`[tool.pytest.ini_options]`** — test configuration (like surefire plugin config in pom.xml)

---

### `app/main.py` — the `@SpringBootApplication`

```python
@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    await check_db_connection()   # run on startup
    yield                         # app is running here
    await engine.dispose()        # run on shutdown
```

`lifespan` is a context manager. Think of it as:
- Code **before** `yield` = `@PostConstruct` / `ApplicationListener<ContextRefreshedEvent>`
- Code **after** `yield` = `@PreDestroy` / shutdown hook

```python
def create_app() -> FastAPI:
    app = FastAPI(
        docs_url="/docs" if not settings.is_production else None,  # hide Swagger in prod
        lifespan=lifespan,
    )
    app.add_middleware(CORSMiddleware, ...)       # filter chain
    app.add_middleware(RequestLoggingMiddleware)  # filter chain
    register_exception_handlers(app)             # @ControllerAdvice
    app.include_router(v1_router)                # @RequestMapping("/api/v1")
    return app

setup_logging()     # configure logback before anything else
app = create_app()  # this 'app' object is what uvicorn serves
```

The `app` variable at the bottom is what uvicorn looks for:
`uvicorn app.main:app` = "in file `app/main.py`, find the object named `app`"

---

### `app/core/config.py` — `application.yml`

```python
class Settings(BaseSettings):
    DATABASE_URL: str          # required — app crashes at startup if missing
    SECRET_KEY: str            # required
    LOG_LEVEL: str = "INFO"    # optional — has default value
    CORS_ORIGINS: list[str] = []
```

`BaseSettings` reads from environment variables and `.env` file automatically.
The class is instantiated **once** at module load: `settings = Settings()`
Everywhere else in the app: `from app.core.config import settings`

This is equivalent to `@ConfigurationProperties(prefix = "")` + `@Value`.

**Fail-fast validation:**
```python
@field_validator("SECRET_KEY")
@classmethod
def secret_key_must_not_be_default(cls, v: str) -> str:
    if v == "change-me-...":
        raise ValueError("SECRET_KEY must be changed")
    return v
```
If this fails, the app crashes **before** accepting any traffic. Same philosophy as Spring's startup context validation.

---

### `app/core/constants.py` — Enums

```python
from enum import StrEnum

class Role(StrEnum):
    ADMIN = "admin"
    USER = "user"
    READONLY = "readonly"
```

`StrEnum` is Python's built-in enum where values are strings.
Java equivalent:
```java
public enum Role { ADMIN, USER, READONLY }
```

Usage: `Role.ADMIN` gives you the string `"admin"` directly (no `.name()` or `.getValue()` needed).

---

### `app/core/security.py` — JWT + password hashing

```python
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

def hash_password(plain_password: str) -> str:
    return pwd_context.hash(plain_password)

def verify_password(plain_password: str, hashed_password: str) -> bool:
    return pwd_context.verify(plain_password, hashed_password)
```

`passlib` handles bcrypt hashing. Java equivalent: `BCryptPasswordEncoder`.

```python
def create_access_token(subject: str | Any, ...) -> str:
    payload = {
        "sub": str(subject),   # user ID
        "exp": expire,         # expiry timestamp
        "jti": str(uuid4()),   # unique token ID (for revocation)
        "type": "access",      # to distinguish from refresh tokens
    }
    return jwt.encode(payload, settings.SECRET_KEY, algorithm="HS256")
```

`str | Any` means "either a string or anything else" — Python's union type.
Java equivalent: `String | Object` (but Java doesn't have this syntax, would use generics).

`decode_token` decodes and validates the JWT. If expired or tampered → raises `UnauthorizedError`.

---

### `app/core/logging.py` — Logback equivalent

```python
def setup_logging() -> None:
    if settings.is_production:
        final_processors = [..., structlog.processors.JSONRenderer()]
    else:
        final_processors = [..., structlog.dev.ConsoleRenderer(colors=True)]

    structlog.configure(processors=final_processors, ...)
```

In **development**: colored human-readable output:
```
2026-04-30T13:56:27Z [info] database_connection_ok
2026-04-30T13:56:28Z [info] request method=POST path=/api/v1/auth/login status_code=200 duration_ms=47
```

In **production**: JSON per line (parseable by Elasticsearch/Datadog/CloudWatch):
```json
{"timestamp": "2026-04-30T13:56:28Z", "level": "info", "event": "request", "method": "POST", "path": "/api/v1/auth/login", "status_code": 200, "duration_ms": 47}
```

Usage anywhere in the app:
```python
import structlog
logger = structlog.get_logger(__name__)
logger.info("user_created", user_id="123", email="x@y.com")
```

This is like Spring's `LoggerFactory.getLogger(getClass())` + MDC.

---

### `app/core/exception_handlers.py` — `@ControllerAdvice`

```python
def register_exception_handlers(app: FastAPI) -> None:

    @app.exception_handler(AppException)
    async def domain_exception_handler(request, exc) -> JSONResponse:
        return _error_response(exc.status_code, exc.error_code, exc.message)

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request, exc) -> JSONResponse:
        errors = [{"field": ..., "message": ...} for e in exc.errors()]
        return JSONResponse(status_code=422, content={...})

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request, exc) -> JSONResponse:
        logger.exception("unhandled_exception", ...)
        return _error_response(500, "INTERNAL_ERROR", "An unexpected error occurred")
```

Every error in the app becomes a normalized JSON:
```json
{
  "error": {
    "code": "USER_NOT_FOUND",
    "message": "User '123' not found"
  }
}
```

No `try/catch` in route handlers. Throw an exception → handler catches it → JSON response.

---

### `app/exceptions/domain.py` — Custom exceptions

```python
class AppException(Exception):
    status_code: int = 500
    error_code: str = "INTERNAL_ERROR"

class NotFoundError(DomainException):
    status_code = 404
    error_code = "NOT_FOUND"

class UserNotFoundError(NotFoundError):
    error_code = "USER_NOT_FOUND"
    def __init__(self, user_id=None):
        super().__init__(f"User '{user_id}' not found")
```

Java equivalent:
```java
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserNotFoundException extends RuntimeException { ... }
```

The hierarchy:
```
Exception (Python built-in)
  └── AppException
        ├── DomainException (4xx)
        │     ├── NotFoundError (404)
        │     │     └── UserNotFoundError
        │     ├── ConflictError (409)
        │     │     └── EmailAlreadyExistsError
        │     ├── UnauthorizedError (401)
        │     │     └── InvalidCredentialsError
        │     └── ForbiddenError (403)
        └── InfrastructureException (5xx) ← add here for external service errors
```

---

### `app/db/base.py` — JPA MetaData

```python
NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",           # index names
    "uq": "uq_%(table_name)s_%(column_0_name)s",  # unique constraint names
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}

class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)
```

All ORM models extend `Base`. The naming convention ensures Alembic generates consistent constraint names across different databases — prevents silent migration failures in production.

Java equivalent: JPA `@Table(name=...)`, `@Index(name=...)` but globally enforced.

---

### `app/db/session.py` — DataSource + `@Transactional`

```python
engine = create_async_engine(
    settings.DATABASE_URL,
    pool_size=10,         # min connections in pool
    max_overflow=20,      # max additional connections
    pool_pre_ping=True,   # test connection before use (testOnBorrow)
    pool_recycle=3600,    # close & reopen connections after 1 hour
)
```

This is your **DataSource** / **HikariCP** configuration.

```python
async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with AsyncSessionLocal() as session:
        try:
            yield session        # give session to the caller
            await session.commit()   # commit on success
        except Exception:
            await session.rollback() # rollback on any error
            raise
```

This is `@Transactional` implemented as a generator:
1. `yield session` — pauses here, gives session to the route handler
2. After route handler returns → `commit()`
3. If exception → `rollback()`

It's used via `Depends(get_session)` — FastAPI calls this automatically.

---

### `app/db/init_db.py` — Startup DB check

```python
async def check_db_connection() -> None:
    async with engine.begin() as conn:
        await conn.execute(text("SELECT 1"))
    logger.info("database_connection_ok")
```

Runs at startup (called from `lifespan` in `main.py`). If the DB is unreachable, the app crashes immediately instead of starting and failing on first request. Fail fast.

---

### `app/models/base_model.py` — `@MappedSuperclass`

```python
class TimestampMixin:
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), onupdate=lambda: datetime.now())

class BaseModel(Base, TimestampMixin):
    __abstract__ = True   # tells SQLAlchemy: no table for this class itself

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    is_deleted: Mapped[bool] = mapped_column(Boolean, default=False)
```

**Why UUID instead of Integer ID?**
With integer IDs, anyone can guess URLs: `/users/1`, `/users/2`, etc. — enumeration attack.
UUID: `/users/550e8400-e29b-41d4-a716-446655440000` — impossible to enumerate.

**Why `is_deleted` instead of DELETE?**
Soft delete: records are never physically removed from DB. Set `is_deleted=True` instead.
All queries automatically filter `is_deleted == False`. This allows:
- Audit trail
- Undo operations
- Referential integrity preservation

Java equivalent:
```java
@MappedSuperclass
public abstract class BaseEntity {
    @Id @GeneratedValue private UUID id;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private boolean isDeleted = false;
}
```

---

### `app/models/user.py` — `@Entity`

```python
class User(BaseModel):           # extends BaseModel = extends @MappedSuperclass
    __tablename__ = "users"      # @Table(name = "users")

    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    hashed_password: Mapped[str] = mapped_column(String(255))
    full_name: Mapped[str] = mapped_column(String(255))
    role: Mapped[str] = mapped_column(String(50), default=Role.USER)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True)
```

Java equivalent:
```java
@Entity @Table(name = "users")
public class User extends BaseEntity {
    @Column(unique = true) private String email;
    private String hashedPassword;
    private String fullName;
    private String role;
    private boolean isActive;
}
```

`Mapped[str]` is Python's way of saying "this column maps to a Python `str`".
SQLAlchemy uses this for type safety and IDE autocomplete.

---

### `app/schemas/common.py` — Generic DTOs

```python
T = TypeVar("T")   # generic type parameter — like <T> in Java

class PaginatedResponse(BaseModel, Generic[T]):
    items: list[T]
    total: int
    page: int
    page_size: int
    pages: int
```

Java equivalent:
```java
public record PaginatedResponse<T>(List<T> items, int total, int page, int pageSize, int pages) {}
```

Usage: `PaginatedResponse[UserListResponse]` = Java's `PaginatedResponse<UserListResponse>`.

---

### `app/schemas/user.py` — Request/Response DTOs

```python
class UserCreate(BaseModel):
    email: EmailStr                              # validates email format automatically
    password: str = Field(min_length=8, max_length=128)
    full_name: str = Field(min_length=1)
    role: Role = Role.USER                       # default value

    @field_validator("password")
    @classmethod
    def password_strength(cls, v: str) -> str:   # custom validator
        if not any(c.isupper() for c in v):
            raise ValueError("Must contain uppercase")
        return v
```

Java equivalent:
```java
public record UserCreate(
    @Email String email,
    @Size(min=8, max=128) String password,
    @NotBlank String fullName,
    Role role
) {}
```

`@field_validator` = `@AssertTrue` / custom `ConstraintValidator`.

```python
class UserResponse(BaseModel):
    model_config = {"from_attributes": True}   # allows creating from ORM object
    id: uuid.UUID
    email: str
    ...
```

`from_attributes = True` means Pydantic can read from a SQLAlchemy model object directly:
```python
user = User(...)           # ORM object
UserResponse.model_validate(user)  # convert to DTO
```

Java equivalent: `UserResponse.from(user)` or MapStruct mapping.

---

### `app/schemas/auth.py` — Auth DTOs

```python
class LoginRequest(BaseModel):
    email: EmailStr
    password: str

class TokenResponse(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"   # default value
```

Simple data containers. Pydantic validates them automatically when FastAPI receives a request.

---

### `app/repositories/base_repository.py` — `JpaRepository`

```python
ModelType = TypeVar("ModelType", bound=Base)   # like <T extends BaseEntity>

class BaseRepository(Generic[ModelType]):       # like JpaRepository<T, UUID>
    def __init__(self, model: type[ModelType], session: AsyncSession):
        self.model = model
        self.session = session

    async def get(self, id: uuid.UUID) -> ModelType | None:
        result = await self.session.execute(
            select(self.model).where(
                self.model.id == id,
                self.model.is_deleted == False,
            )
        )
        return result.scalar_one_or_none()   # None if not found
```

`select(self.model).where(...)` = `SELECT * FROM table WHERE ...`
This is SQLAlchemy's query builder — like JPQL / Criteria API but with Python syntax.

```python
    async def create(self, obj: ModelType) -> ModelType:
        self.session.add(obj)         # like entityManager.persist(obj)
        await self.session.flush()    # send SQL to DB (but don't commit yet)
        await self.session.refresh(obj)  # reload from DB to get generated values
        return obj
```

`flush()` vs `commit()`:
- `flush()` = send SQL to the DB within the current transaction (not visible to others yet)
- `commit()` = make changes permanent and visible

```python
    async def soft_delete(self, id: uuid.UUID) -> None:
        obj = await self.get_or_raise(id)
        obj.is_deleted = True         # just set the flag
        await self.session.flush()    # no DELETE SQL — just an UPDATE
```

---

### `app/repositories/user_repository.py` — Custom Repository

```python
class UserRepository(BaseRepository[User]):     # extends BaseRepository<User>
    def __init__(self, session: AsyncSession):
        super().__init__(User, session)         # pass model class to parent

    async def find_by_email(self, email: str) -> User | None:
        result = await self.session.execute(
            select(User).where(User.email == email, User.is_deleted == False)
        )
        return result.scalar_one_or_none()
```

Java equivalent:
```java
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
}
```

The difference: in Spring Data JPA, `findByEmail` is auto-generated from the method name.
In SQLAlchemy, you write the query explicitly. More verbose but more transparent.

---

### `app/services/user_service.py` — `@Service`

```python
class UserService:
    def __init__(self, session: AsyncSession):
        self.repo = UserRepository(session)   # repo is created here, not injected

    async def create_user(self, data: UserCreate) -> User:
        if await self.repo.email_exists(data.email):
            raise EmailAlreadyExistsError(data.email)   # domain exception → 409

        user = User(
            email=data.email,
            hashed_password=hash_password(data.password),  # NEVER store plain password
            full_name=data.full_name,
            role=data.role,
        )
        user = await self.repo.create(user)
        logger.info("user_created", user_id=str(user.id), email=user.email)
        return user
```

**Key difference from Java:** In Spring, you `@Autowired` the repository and it's a singleton.
Here, `UserService(session)` creates a **new instance per request** with the request's DB session.
This is simpler and avoids concurrency issues — no shared state between requests.

---

### `app/services/auth_service.py` — `@Service`

```python
async def login(self, data: LoginRequest) -> TokenResponse:
    user = await self.repo.find_by_email(data.email)
    if user is None or not verify_password(data.password, user.hashed_password):
        raise InvalidCredentialsError()   # same error for both cases — security best practice
        # DON'T say "email not found" vs "wrong password" — attacker would learn valid emails
```

Important security note: the same error is returned whether the email doesn't exist OR the password is wrong. This prevents **user enumeration** — an attacker can't figure out which emails are registered.

```python
async def refresh(self, refresh_token: str) -> TokenResponse:
    payload = decode_token(refresh_token)
    if payload.get("type") != "refresh":
        raise UnauthorizedError("Invalid token type")
    # ... issue new tokens
```

Always check the token `type` field — prevents using an access token as a refresh token.

---

### `app/api/deps.py` — `@Autowired` + SecurityContext

```python
DBSession = Annotated[AsyncSession, Depends(get_session)]
```

`Annotated[AsyncSession, Depends(get_session)]` is FastAPI's way of saying:
"This parameter should be an `AsyncSession`, obtained by calling `get_session()`"

Java equivalent: `@Autowired SessionFactory sessionFactory`

```python
async def get_current_user(
    token: Annotated[str, Depends(oauth2_scheme)],   # extract Bearer token from header
    db: DBSession,
) -> User:
    payload = decode_token(token)           # validate JWT
    user_id = uuid.UUID(payload.get("sub")) # extract user ID
    user = await UserRepository(db).get(user_id)
    structlog.contextvars.bind_contextvars(user_id=str(user.id))  # like MDC.put("userId", ...)
    return user

CurrentUser = Annotated[User, Depends(get_current_user)]
```

`CurrentUser` is a type alias. In any route, just declare:
```python
async def get_me(current_user: CurrentUser) -> UserResponse:
```
FastAPI automatically calls `get_current_user`, validates the JWT, fetches the user.

Java equivalent: `SecurityContextHolder.getContext().getAuthentication().getPrincipal()`

```python
def require_roles(*roles: Role):
    async def _check(current_user: CurrentUser) -> User:
        if current_user.role not in [r.value for r in roles]:
            raise ForbiddenError(...)
    return _check
```

Factory function that returns a dependency. Usage:
```python
dependencies=[Depends(require_roles(Role.ADMIN))]
```

Java equivalent: `@PreAuthorize("hasRole('ADMIN')")`

---

### `app/api/middleware/request_logging.py` — `OncePerRequestFilter`

```python
class RequestLoggingMiddleware(BaseHTTPMiddleware):
    SKIP_PATHS = {"/api/v1/health/live", "/api/v1/health/ready"}  # don't log K8s probes

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        if request.url.path in self.SKIP_PATHS:
            return await call_next(request)   # skip logging for health probes

        start = time.perf_counter()
        response = await call_next(request)   # call_next = FilterChain.doFilter()
        duration_ms = round((time.perf_counter() - start) * 1000, 2)

        logger.info("request",
            method=request.method,
            path=request.url.path,
            status_code=response.status_code,
            duration_ms=duration_ms,
        )
        return response
```

`call_next(request)` = Java's `filterChain.doFilter(request, response)`.
Code before it runs before the handler; code after runs after.

---

### `app/api/v1/router.py` — Route aggregator

```python
router = APIRouter(prefix="/api/v1")   # all routes under /api/v1

router.include_router(health.router)   # adds /api/v1/health, /health/live, /health/ready
router.include_router(auth.router)     # adds /api/v1/auth/login, /auth/refresh
router.include_router(users.router)    # adds /api/v1/users/...
```

Java equivalent:
```java
@RequestMapping("/api/v1")
public class ApiV1Router {
    // aggregates all controllers
}
```

When adding a new feature, you create a new route file and add it here.

---

### `app/api/v1/routes/health.py` — Health endpoints

Three endpoints mapped to K8s probe types:

```python
@router.get("/health/live")
async def liveness() -> dict:
    return {"status": "ok"}   # always 200 — just proves the process is alive
```
K8s **liveness probe**: if this fails → pod is killed and restarted.
**Never check DB here** — a slow DB shouldn't restart your app.

```python
@router.get("/health/ready")
async def readiness() -> JSONResponse:
    try:
        await conn.execute(text("SELECT 1"))
        checks["database"] = "ok"
    except Exception as e:
        checks["database"] = f"error: {e}"
        healthy = False
    return JSONResponse(status_code=200 if healthy else 503, ...)
```
K8s **readiness probe**: if this returns non-2xx → pod removed from load balancer rotation.
The pod keeps running (not restarted), just stops receiving traffic until DB recovers.

Java equivalent: Spring Actuator `/actuator/health` with liveness/readiness groups.

---

### `app/api/v1/routes/auth.py` — Auth endpoints

```python
router = APIRouter(prefix="/auth", tags=["auth"])   # all routes prefixed /auth, grouped in Swagger

@router.post("/login", response_model=TokenResponse)
async def login(data: LoginRequest, db: DBSession) -> TokenResponse:
    return await AuthService(db).login(data)
```

`@router.post("/login")` = Java's `@PostMapping("/login")`
`response_model=TokenResponse` = tells FastAPI to serialize response using `TokenResponse` schema
`data: LoginRequest` = FastAPI reads request body and validates it as `LoginRequest`
`db: DBSession` = FastAPI injects a DB session (via `Depends`)

No `@RequestBody` annotation needed — FastAPI detects it automatically from the type.

---

### `app/api/v1/routes/users.py` — `@RestController`

```python
@router.post("", response_model=UserResponse, status_code=status.HTTP_201_CREATED,
             dependencies=[Depends(require_roles(Role.ADMIN))])
async def create_user(data: UserCreate, db: DBSession) -> UserResponse:
    user = await UserService(db).create_user(data)
    return UserResponse.model_validate(user)   # convert ORM → DTO
```

Java equivalent:
```java
@PostMapping
@PreAuthorize("hasRole('ADMIN')")
@ResponseStatus(HttpStatus.CREATED)
public UserResponse createUser(@RequestBody @Valid UserCreate data) {
    return userMapper.toResponse(userService.createUser(data));
}
```

```python
@router.get("/{user_id}", response_model=UserResponse,
            dependencies=[Depends(require_roles(Role.ADMIN))])
async def get_user(user_id: uuid.UUID, db: DBSession) -> UserResponse:
```

`{user_id}` in the path → FastAPI automatically parses it as `uuid.UUID`.
If you pass a non-UUID string in the URL → automatic 422 validation error.

Java equivalent: `@PathVariable UUID userId`

```python
@router.get("", ...)
async def list_users(
    db: DBSession,
    page: int = Query(1, ge=1),           # query param, min value 1
    page_size: int = Query(20, ge=1, le=100),  # query param, min 1 max 100
) -> PaginatedResponse[UserListResponse]:
```

Java equivalent: `@RequestParam(defaultValue="1") @Min(1) int page`

---

### `alembic/` — Flyway/Liquibase equivalent

**How migrations work:**

1. You change a model in `app/models/`
2. Run: `make migration MSG="add phone to users"`
3. Alembic generates a migration file in `alembic/versions/` automatically
4. Run: `make migrate` → executes the migration against the DB

```python
# alembic/env.py — key parts:

import app.models.user  # noqa: F401   ← CRITICAL: must import all models
                                        # Alembic can only detect tables it knows about

config.set_main_option("sqlalchemy.url", settings.DATABASE_URL)  # reads from .env
```

The `# noqa: F401` comment tells the linter "I know this import looks unused, it's intentional".

Generated migration file looks like:
```python
def upgrade() -> None:
    op.add_column("users", sa.Column("phone", sa.String(20), nullable=True))

def downgrade() -> None:
    op.drop_column("users", "phone")
```

Every migration has an `upgrade()` and `downgrade()` — Flyway equivalent.

---

### `.docker/Dockerfile` — Multi-stage build

```dockerfile
# Stage 1: Builder — has pip/uv, installs dependencies
FROM python:3.12-slim AS builder
RUN pip install uv
RUN uv venv .venv
RUN uv pip install --python .venv/bin/python fastapi uvicorn sqlalchemy ...

# Stage 2: Runtime — lean image, NO build tools
FROM python:3.12-slim AS runtime
RUN useradd -u 1001 appuser     # non-root user (K8s security requirement)
COPY --from=builder /app/.venv ./.venv   # copy only the installed packages
COPY app/ ./app/
USER appuser                    # never run as root
ENTRYPOINT ["./entrypoint.sh"]
```

**Why multi-stage?**
- Builder image has `uv`, `pip`, `gcc` — needed to install packages
- Runtime image has NONE of these — attack surface reduced, image is smaller
- Final image: ~200MB instead of ~800MB

**Why non-root user?**
K8s `securityContext.runAsNonRoot: true` requires it. Good security practice.

---

### `docker-compose.yml` — Local stack

```yaml
services:
  app:
    depends_on:
      postgres:
        condition: service_healthy   # wait for postgres healthcheck to pass
```

`service_healthy` = waits for `pg_isready` to return OK before starting the app.
Without this, app starts, tries to connect to DB, fails, crashes.

```yaml
  postgres:
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U app -d appdb"]
```

```yaml
    volumes:
      - postgres_data:/var/lib/postgresql/data   # data survives docker compose down
```

Named volume = data is persisted between restarts. Without this, every `docker compose down` wipes your DB.

`docker-compose.override.yml` is automatically applied on top of `docker-compose.yml` in development:
- Mounts `./app` as a volume → code changes are reflected without rebuilding
- Sets `ENVIRONMENT=development` → colored logs, Swagger enabled

---

### `Makefile` — Maven wrapper equivalent

```makefile
migrate:
    alembic upgrade head             # mvn flyway:migrate

migration:
    alembic revision --autogenerate -m "$(MSG)"   # generate migration file

test:
    pytest -v --tb=short             # mvn test

lint:
    ruff check app tests             # mvn checkstyle:check

format:
    ruff format app tests            # mvn spotless:apply

typecheck:
    mypy app                         # like Java compiler strict mode
```

Usage:
```bash
make migrate                          # apply pending migrations
make migration MSG="add phone column" # generate new migration
make test                             # run all tests
make run                              # docker compose up --build
```

---

## 5. Key Python Concepts Explained for Java Devs

### `async def` / `await` in detail

```python
# This is WRONG — will not actually await the DB call:
def get_user(id):
    user = repo.get(id)   # returns a coroutine object, not a User!
    return user

# This is CORRECT:
async def get_user(id):
    user = await repo.get(id)   # actually runs the async DB call
    return user
```

Rule: if a function contains `await`, it must be `async def`.
If you call an `async def` function, you must `await` it.

### `Annotated[Type, Depends(...)]` — dependency injection

```python
DBSession = Annotated[AsyncSession, Depends(get_session)]

async def some_route(db: DBSession):
    # FastAPI sees: "db should be AsyncSession, obtained by calling get_session()"
    # It calls get_session(), gets the session, passes it here automatically
```

This is how FastAPI does DI without a container. No `@Autowired`, no beans.

### `Generic[T]` — generics

```python
T = TypeVar("T")          # declare the type parameter

class PaginatedResponse(BaseModel, Generic[T]):   # like class Foo<T>
    items: list[T]

# Usage:
PaginatedResponse[UserListResponse]   # like Foo<UserListResponse>
```

### `| None` — nullable types

```python
# Java: Optional<User> or @Nullable User
# Python:
def find_by_email(email: str) -> User | None:
```

`User | None` means "either a User or None (null)".
Python 3.10+ syntax. Older Python uses `Optional[User]` from `typing`.

### `@classmethod` — static factory methods

```python
class UserCreate(BaseModel):
    @field_validator("password")
    @classmethod                    # like Java's static method
    def check_strength(cls, v: str) -> str:
        # cls = the class itself (like Class<UserCreate>)
        # v = the value being validated
        return v
```

### f-strings — string interpolation

```python
# Java: String.format("User '%s' not found", userId)
# Python:
f"User '{user_id}' not found"    # f before the quote = format string
```

### List comprehensions

```python
# Java: errors.stream().map(e -> new ErrorDetail(e.field(), e.message())).toList()
# Python:
errors = [{"field": e["loc"], "message": e["msg"]} for e in exc.errors()]
#          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^         ^^^^^^^^^^^^^^^^^^
#          what to produce for each element            where to iterate
```

### `*args` — variadic arguments

```python
def require_roles(*roles: Role):   # *roles captures all arguments as a tuple
    # Usage: require_roles(Role.ADMIN, Role.USER)
    # roles = (Role.ADMIN, Role.USER)
```

Java equivalent: `String... roles` varargs.

---

## 6. How to Add a New Feature (Step-by-Step)

**Example: Add a `Product` entity with full CRUD**

### Step 1 — Create the model (`app/models/product.py`)

```python
from sqlalchemy import String, Numeric
from sqlalchemy.orm import Mapped, mapped_column
from app.models.base_model import BaseModel

class Product(BaseModel):
    __tablename__ = "products"

    name: Mapped[str] = mapped_column(String(255), nullable=False)
    price: Mapped[float] = mapped_column(Numeric(10, 2), nullable=False)
    description: Mapped[str] = mapped_column(String(1000), nullable=True)
```

### Step 2 — Create the schemas (`app/schemas/product.py`)

```python
from pydantic import BaseModel, Field

class ProductCreate(BaseModel):
    name: str = Field(min_length=1, max_length=255)
    price: float = Field(gt=0)

class ProductResponse(BaseModel):
    model_config = {"from_attributes": True}
    id: uuid.UUID
    name: str
    price: float
```

### Step 3 — Create the repository (`app/repositories/product_repository.py`)

```python
from app.models.product import Product
from app.repositories.base_repository import BaseRepository

class ProductRepository(BaseRepository[Product]):
    def __init__(self, session):
        super().__init__(Product, session)
    # BaseRepository already gives you: get, get_or_raise, list, create, update, soft_delete
    # Add custom queries here if needed
```

### Step 4 — Create the service (`app/services/product_service.py`)

```python
from app.repositories.product_repository import ProductRepository

class ProductService:
    def __init__(self, session):
        self.repo = ProductRepository(session)

    async def create(self, data: ProductCreate) -> Product:
        product = Product(name=data.name, price=data.price)
        return await self.repo.create(product)
```

### Step 5 — Create the routes (`app/api/v1/routes/products.py`)

```python
from fastapi import APIRouter
from app.api.deps import DBSession
from app.services.product_service import ProductService

router = APIRouter(prefix="/products", tags=["products"])

@router.post("", response_model=ProductResponse, status_code=201)
async def create_product(data: ProductCreate, db: DBSession) -> ProductResponse:
    product = await ProductService(db).create(data)
    return ProductResponse.model_validate(product)
```

### Step 6 — Register in router (`app/api/v1/router.py`)

```python
from app.api.v1.routes import auth, health, users, products  # add products

router.include_router(products.router)   # add this line
```

### Step 7 — Register model in Alembic (`alembic/env.py`)

```python
import app.models.user     # noqa: F401
import app.models.product  # noqa: F401  ← add this
```

### Step 8 — Generate and apply migration

```bash
make migration MSG="add products table"   # generates alembic/versions/xxx_add_products_table.py
make migrate                              # applies it to DB
```

Done. You now have a full CRUD for products following the same pattern as users.

---

## Summary

The flow for every feature is always:

```
.env / config.py          → configuration
models/                   → DB table (ORM)
schemas/                  → request/response shape (validation)
repositories/             → DB queries (data access)
services/                 → business logic
routes/                   → HTTP endpoints
router.py                 → registration
alembic/                  → migration
```

This is identical to Spring Boot's layered architecture — just with different tool names.
