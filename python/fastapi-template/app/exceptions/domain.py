"""
Domain exception hierarchy.
Spring equivalent: @ResponseStatus exceptions extending RuntimeException.
Mapped to HTTP responses by global exception handlers in core/exception_handlers.py.
"""


class AppException(Exception):
    """Base exception for all application errors."""

    status_code: int = 500
    error_code: str = "INTERNAL_ERROR"

    def __init__(self, message: str) -> None:
        self.message = message
        super().__init__(message)


class DomainException(AppException):
    """Base for 4xx client errors."""

    status_code = 400
    error_code = "DOMAIN_ERROR"


class NotFoundError(DomainException):
    status_code = 404
    error_code = "NOT_FOUND"


class ConflictError(DomainException):
    status_code = 409
    error_code = "CONFLICT"


class UnauthorizedError(DomainException):
    status_code = 401
    error_code = "UNAUTHORIZED"


class ForbiddenError(DomainException):
    status_code = 403
    error_code = "FORBIDDEN"


class ValidationError(DomainException):
    status_code = 422
    error_code = "VALIDATION_ERROR"


# Specific domain errors
class UserNotFoundError(NotFoundError):
    error_code = "USER_NOT_FOUND"

    def __init__(self, user_id: str | None = None) -> None:
        msg = f"User '{user_id}' not found" if user_id else "User not found"
        super().__init__(msg)


class EmailAlreadyExistsError(ConflictError):
    error_code = "EMAIL_ALREADY_EXISTS"

    def __init__(self, email: str) -> None:
        super().__init__(f"Email '{email}' is already registered")


class InvalidCredentialsError(UnauthorizedError):
    error_code = "INVALID_CREDENTIALS"

    def __init__(self) -> None:
        super().__init__("Invalid email or password")
