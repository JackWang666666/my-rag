package com.rag.controller;

import com.rag.model.dto.FeedbackRequest;
import com.rag.service.FeedbackService;
import com.rag.service.PermissionService;
import com.rag.store.MetadataStore;
import com.rag.web.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Thumbs up/down on assistant messages (docs/CONTRACT.md section 5).
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final PermissionService permissions;

    public FeedbackController(FeedbackService feedbackService, PermissionService permissions) {
        this.feedbackService = feedbackService;
        this.permissions = permissions;
    }

    @PostMapping
    public Map<String, Boolean> submit(@RequestBody FeedbackRequest req) {
        CurrentUser user = CurrentUser.get();
        // Rating a message in someone else's session was previously possible.
        permissions.requireSessionAccess(req.getSessionId(), user);

        boolean success = feedbackService.submitFeedback(req.getSessionId(),
                req.getMessageIndex(), user.username(), req.getRating(), req.getComment());
        return Map.of("success", success);
    }

    /** Admins see site-wide numbers; regular users see their own. */
    @GetMapping("/stats")
    public MetadataStore.FeedbackStats stats() {
        CurrentUser user = CurrentUser.get();
        return feedbackService.getStats(user.isAdmin() ? null : user.username());
    }
}
