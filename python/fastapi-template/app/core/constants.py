from enum import StrEnum


class Role(StrEnum):
    ADMIN = "admin"
    USER = "user"
    READONLY = "readonly"


class TokenType(StrEnum):
    ACCESS = "access"
    REFRESH = "refresh"


class Environment(StrEnum):
    DEVELOPMENT = "development"
    STAGING = "staging"
    PRODUCTION = "production"
