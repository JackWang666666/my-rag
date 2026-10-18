"""Feedback API routes."""

from fastapi import APIRouter, Depends, HTTPException

from app.core.auth import get_current_user, is_admin
from app.core.permissions import require_session_access
from app.models.schemas import FeedbackRequest, FeedbackStatsResponse
from app.services.feedback import get_feedback_stats, submit_feedback

router = APIRouter(prefix="/api/feedback", tags=["feedback"])


@router.post("")
async def post_feedback(
    req: FeedbackRequest,
    current_user: dict = Depends(get_current_user),
):
    """Record thumbs up/down on an assistant message in the caller's session."""
    require_session_access(req.session_id, current_user)

    try:
        success = submit_feedback(
            session_id=req.session_id,
            message_index=req.message_index,
            owner=current_user["username"],
            rating=req.rating,
            comment=req.comment,
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    return {"success": success}


@router.get("/stats", response_model=FeedbackStatsResponse)
async def feedback_stats(current_user: dict = Depends(get_current_user)):
    """Feedback stats. Admins see site-wide numbers, users see their own."""
    owner = None if is_admin(current_user) else current_user["username"]
    return FeedbackStatsResponse(**get_feedback_stats(owner))
