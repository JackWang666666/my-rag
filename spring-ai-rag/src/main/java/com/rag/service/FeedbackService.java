package com.rag.service;

import com.rag.store.MetadataStore;
import com.rag.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Thumbs up/down on assistant messages, stored in the shared {@code feedback}
 * table so both backends (and restarts) see the same ratings.
 */
@Service
@Slf4j
public class FeedbackService {

    private final MetadataStore store;

    public FeedbackService(MetadataStore store) {
        this.store = store;
    }

    public boolean submitFeedback(String sessionId, int messageIndex, String owner, String rating,
                                  String comment) {
        if (!"up".equals(rating) && !"down".equals(rating)) {
            throw ApiException.badRequest("rating must be 'up' or 'down'");
        }
        if (messageIndex < 0) {
            throw ApiException.badRequest("messageIndex must not be negative");
        }
        store.upsertFeedback(sessionId, messageIndex, owner, rating, comment);
        return true;
    }

    /**
     * {@code owner == null} means site-wide (admin view).
     *
     * <p>{@code satisfactionRate} is a 0-1 ratio. It used to be a percentage here
     * while the Python backend returned a ratio, so the same data rendered as
     * "83.3%" on one backend and "0.83%" on the other.
     */
    public MetadataStore.FeedbackStats getStats(String owner) {
        return store.feedbackStats(owner);
    }
}
