"""Authentication API routes."""

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from app.core.auth import (
    MAX_PASSWORD_BYTES,
    MAX_USERNAME_LENGTH,
    MIN_PASSWORD_LENGTH,
    MIN_USERNAME_LENGTH,
    authenticate_user,
    create_access_token,
    register_user,
)

router = APIRouter(prefix="/api/auth", tags=["auth"])


#: In characters, where MAX_PASSWORD_BYTES is in UTF-8 bytes. The same number is
#: safe as a character cap because a character is never fewer than one byte, so
#: this never rejects a password the byte check would have accepted.
_MAX_PASSWORD_CHARS = MAX_PASSWORD_BYTES


class LoginRequest(BaseModel):
    username: str = Field(..., min_length=1, max_length=MAX_USERNAME_LENGTH)
    # No minimum here on purpose: a too-short password must come back as 401
    # "invalid username or password", not as a 422 that reveals the policy.
    password: str = Field(..., min_length=1, max_length=_MAX_PASSWORD_CHARS)


class RegisterRequest(BaseModel):
    username: str = Field(
        ..., min_length=MIN_USERNAME_LENGTH, max_length=MAX_USERNAME_LENGTH
    )
    password: str = Field(
        ..., min_length=MIN_PASSWORD_LENGTH, max_length=_MAX_PASSWORD_CHARS
    )


class TokenResponse(BaseModel):
    token: str
    username: str
    role: str


@router.post("/login", response_model=TokenResponse)
async def login(req: LoginRequest):
    user = authenticate_user(req.username, req.password)
    if not user:
        raise HTTPException(status_code=401, detail="Invalid username or password")

    token = create_access_token(user["username"], user["role"])
    return TokenResponse(token=token, username=user["username"], role=user["role"])


@router.post("/register", response_model=TokenResponse)
async def register(req: RegisterRequest):
    try:
        user = register_user(req.username, req.password)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    token = create_access_token(user["username"], user["role"])
    return TokenResponse(token=token, username=user["username"], role=user["role"])
