"""User authentication with JWT tokens.

Users live in the shared SQLite store (see app.core.store) so accounts survive a
restart and are visible to the Spring backend too. The signing secret comes from
JWT_SECRET; set the same value in both backends for tokens to be interchangeable.
"""

import logging
from datetime import datetime, timedelta, timezone
from typing import Optional

import bcrypt
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt

from app.core import store
from app.core.config import settings

logger = logging.getLogger(__name__)

ALGORITHM = "HS256"

#: passlib's CryptContext used to sit here, but passlib 1.7.4 (unmaintained since
#: 2020) probes the bcrypt backend by hashing a 234-byte string, and bcrypt >= 4.1
#: raises on anything over 72 bytes instead of truncating -- so the first login
#: attempt died with "password cannot be longer than 72 bytes". Calling bcrypt
#: directly removes both the dead layer and the version pin it would have needed.
#: The output is the same `$2b$` hash Spring's BCryptPasswordEncoder verifies.
BCRYPT_ROUNDS = 12

security = HTTPBearer(auto_error=False)


def init_admin() -> None:
    """Create the bootstrap admin account if it does not exist yet."""
    if store.get_user(settings.ADMIN_USERNAME) is None:
        store.create_user(
            settings.ADMIN_USERNAME,
            hash_password(settings.ADMIN_PASSWORD),
            role="admin",
        )
        logger.info("Created bootstrap admin user '%s'", settings.ADMIN_USERNAME)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    try:
        return bcrypt.checkpw(
            plain_password.encode("utf-8"), hashed_password.encode("utf-8")
        )
    except (ValueError, TypeError):
        # A malformed hash in the DB (e.g. written by a different tool), or a
        # password over the 72-byte limit. Either way it is a failed login, not a
        # 500 on the login endpoint.
        return False


def hash_password(password: str) -> str:
    """Hash a password. Raises ValueError above MAX_PASSWORD_BYTES."""
    return bcrypt.hashpw(
        password.encode("utf-8"), bcrypt.gensalt(rounds=BCRYPT_ROUNDS)
    ).decode("ascii")


def create_access_token(username: str, role: str) -> str:
    expire = datetime.now(timezone.utc) + timedelta(
        hours=settings.ACCESS_TOKEN_EXPIRE_HOURS
    )
    payload = {
        "sub": username,
        "role": role,
        "exp": expire,
    }
    return jwt.encode(payload, settings.JWT_SECRET, algorithm=ALGORITHM)


#: Same limits as the Spring backend's UserService; both write the same table.
MIN_USERNAME_LENGTH = 2
MAX_USERNAME_LENGTH = 50
MIN_PASSWORD_LENGTH = 6
#: bcrypt only hashes the first 72 bytes, and implementations disagree on the
#: remainder -- OpenBSD truncates, Spring's jBCrypt-derived code folds them in,
#: current pyca/bcrypt refuses. Rejecting here keeps every account usable from
#: both backends. Counted in UTF-8 bytes, so a Chinese password caps out at 24
#: characters.
MAX_PASSWORD_BYTES = 72


def register_user(username: str, password: str) -> dict:
    """Register a new user."""
    username = username.strip()
    if not MIN_USERNAME_LENGTH <= len(username) <= MAX_USERNAME_LENGTH:
        raise ValueError(
            "Username must be between %d and %d characters"
            % (MIN_USERNAME_LENGTH, MAX_USERNAME_LENGTH)
        )
    if len(password) < MIN_PASSWORD_LENGTH:
        raise ValueError(
            "Password must be at least %d characters" % MIN_PASSWORD_LENGTH
        )
    if len(password.encode("utf-8")) > MAX_PASSWORD_BYTES:
        raise ValueError(
            "Password must be at most %d bytes" % MAX_PASSWORD_BYTES
        )
    if store.get_user(username) is not None:
        raise ValueError("Username already exists")

    store.create_user(username, hash_password(password), role="user")
    return {"username": username, "role": "user"}


def authenticate_user(username: str, password: str) -> Optional[dict]:
    """Authenticate a user and return user data if valid."""
    user = store.get_user(username)
    if not user:
        return None
    if not verify_password(password, user["hashed_password"]):
        return None
    return {"username": user["username"], "role": user["role"]}


def _decode(token: str) -> Optional[dict]:
    try:
        payload = jwt.decode(token, settings.JWT_SECRET, algorithms=[ALGORITHM])
    except JWTError:
        return None
    username = payload.get("sub")
    if not username:
        return None
    return {"username": username, "role": payload.get("role", "user")}


async def get_current_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(security),
) -> dict:
    """Dependency: extract and validate current user from JWT token."""
    if not credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Not authenticated",
            headers={"WWW-Authenticate": "Bearer"},
        )

    user = _decode(credentials.credentials)
    if user is None:
        raise HTTPException(status_code=401, detail="Invalid or expired token")
    return user


async def get_optional_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(security),
) -> Optional[dict]:
    """Dependency: optionally extract user (returns None if no token)."""
    if not credentials:
        return None
    return _decode(credentials.credentials)


def require_admin(current_user: dict) -> None:
    """Raise 403 unless the caller is an admin."""
    if current_user.get("role") != "admin":
        raise HTTPException(status_code=403, detail="Admin privileges required")


def is_admin(current_user: dict) -> bool:
    return current_user.get("role") == "admin"
