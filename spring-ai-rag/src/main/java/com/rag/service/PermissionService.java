package com.rag.service;

import com.rag.store.MetadataStore;
import com.rag.web.ApiException;
import com.rag.web.CurrentUser;
import org.springframework.stereotype.Service;

/**
 * Authorization rules from {@code docs/CONTRACT.md} section 6.
 *
 * <p>Java mirror of {@code langchain-rag/app/core/permissions.py}. Centralised so
 * the "global or owner or admin" condition is written once instead of being
 * re-typed (and mis-typed) in every endpoint.
 */
@Service
public class PermissionService {

    private final MetadataStore store;

    public PermissionService(MetadataStore store) {
        this.store = store;
    }

    /** Load a knowledge base or raise 404. */
    public MetadataStore.KbRow requireKb(String kbId) {
        MetadataStore.KbRow kb = kbId == null ? null : store.getKb(kbId);
        if (kb == null) {
            throw ApiException.notFound("Knowledge base not found");
        }
        return kb;
    }

    /** Global KBs are readable by everyone; private ones by owner or admin. */
    public MetadataStore.KbRow requireKbRead(String kbId, CurrentUser user) {
        MetadataStore.KbRow kb = requireKb(kbId);
        if (kb.isGlobal() || user.isAdmin() || kb.owner().equals(user.username())) {
            return kb;
        }
        throw ApiException.forbidden("Permission denied");
    }

    /** Only admins may write to global KBs; private ones by owner or admin. */
    public MetadataStore.KbRow requireKbWrite(String kbId, CurrentUser user) {
        MetadataStore.KbRow kb = requireKb(kbId);
        if (kb.isGlobal()) {
            if (user.isAdmin()) {
                return kb;
            }
            throw ApiException.forbidden("Only admin can modify global knowledge bases");
        }
        if (user.isAdmin() || kb.owner().equals(user.username())) {
            return kb;
        }
        throw ApiException.forbidden("Permission denied");
    }

    /**
     * Load a session, or raise unless the caller owns it (or is admin).
     *
     * <p>Without this check any logged-in user could read or delete anyone's
     * conversation just by knowing a session id.
     */
    public MetadataStore.SessionRow requireSessionAccess(String sessionId, CurrentUser user) {
        MetadataStore.SessionRow session = sessionId == null ? null : store.getSession(sessionId);
        if (session == null) {
            throw ApiException.notFound("Session not found");
        }
        if (user.isAdmin() || session.owner().equals(user.username())) {
            return session;
        }
        // Deliberately 404, not 403: a 403 confirms the session exists.
        throw ApiException.notFound("Session not found");
    }

    /** Load a document and check permission on its knowledge base. */
    public MetadataStore.DocRow requireDocumentAccess(String docId, CurrentUser user, boolean write) {
        MetadataStore.DocRow doc = docId == null ? null : store.getDocument(docId);
        if (doc == null) {
            throw ApiException.notFound("Document not found");
        }
        if (write) {
            requireKbWrite(doc.kbId(), user);
        } else {
            requireKbRead(doc.kbId(), user);
        }
        return doc;
    }

    public void requireAdmin(CurrentUser user) {
        if (!user.isAdmin()) {
            throw ApiException.forbidden("Admin privileges required");
        }
    }
}
