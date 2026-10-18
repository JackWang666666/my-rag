"""User feedback collection for answer quality improvement.

Persisted in SQLite so the stats survive a restart and are shared with the
Spring backend.
"""

import logging
from typing import Any, Dict, Optional

from app.core import store

logger = logging.getLogger(__name__)

VALID_RATINGS = ("up", "down")


def submit_feedback(
    session_id: str,
    message_index: int,
    owner: str,
    rating: str,
    comment: Optional[str] = None,
) -> bool:
    """Record feedback for one assistant message. Re-submitting overwrites."""
    if rating not in VALID_RATINGS:
        raise ValueError("rating must be 'up' or 'down'")

    store.upsert_feedback(
        session_id=session_id,
        message_index=message_index,
        owner=owner,
        rating=rating,
        comment=comment,
    )
    logger.info("Feedback received: %s:%s -> %s", session_id, message_index, rating)
    return True


def get_feedback_stats(owner: Optional[str] = None) -> Dict[str, Any]:
    """Aggregated feedback statistics.

    `satisfaction_rate` is a 0-1 ratio (the old implementation returned a
    percentage from Python while the Java side returned a ratio).
    """
    return store.feedback_stats(owner)
