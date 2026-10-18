package com.rag.controller;

import com.rag.config.SettingsStore;
import com.rag.model.dto.SettingsRequest;
import com.rag.model.dto.SettingsResponse;
import com.rag.service.PermissionService;
import com.rag.web.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runtime settings (docs/CONTRACT.md section 5).
 *
 * <p>Reading is open to any authenticated user (secrets are never in the response
 * shape); writing is admin-only, matching the Python backend. The previous version
 * kept its own {@code AtomicReference} of four fields, so a PUT reported success
 * and changed nothing that the services actually read.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsStore settings;
    private final PermissionService permissions;

    public SettingsController(SettingsStore settings, PermissionService permissions) {
        this.settings = settings;
        this.permissions = permissions;
    }

    @GetMapping
    public SettingsResponse get() {
        return settings.current();
    }

    @PutMapping
    public SettingsResponse update(@RequestBody SettingsRequest request) {
        permissions.requireAdmin(CurrentUser.get());
        return settings.update(request);
    }
}
