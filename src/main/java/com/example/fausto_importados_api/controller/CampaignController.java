package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.concurrency.IdempotencyService;
import com.example.fausto_importados_api.dto.campaign.CampaignDetail;
import com.example.fausto_importados_api.dto.campaign.CampaignFilter;
import com.example.fausto_importados_api.dto.campaign.CampaignInput;
import com.example.fausto_importados_api.dto.campaign.CampaignSummary;
import com.example.fausto_importados_api.dto.campaign.VersionRequest;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.security.reauth.ReauthInterceptor;
import com.example.fausto_importados_api.security.reauth.RequiresReauth;
import com.example.fausto_importados_api.services.CampaignService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Campanhas sazonais no painel. Apenas administradores. */
@RestController
@RequestMapping("/api/admin/campaigns")
@PreAuthorize("hasRole('ADMIN')")
public class CampaignController {

    private static final int MAX_PAGE_SIZE = 100;

    private final CampaignService campaigns;
    private final IdempotencyService idempotency;
    private final ReauthInterceptor reauth;

    public CampaignController(CampaignService campaigns, IdempotencyService idempotency, ReauthInterceptor reauth) {
        this.campaigns = campaigns;
        this.idempotency = idempotency;
        this.reauth = reauth;
    }

    @GetMapping
    public PagedModel<CampaignSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        CampaignFilter filter = CampaignFilter.parse(status, phase, type, from, to, q);
        return new PagedModel<>(campaigns.search(
                filter, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)));
    }

    @GetMapping("/{id}")
    public CampaignDetail get(@PathVariable UUID id) {
        return campaigns.get(id);
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @RequestBody CampaignInput input,
            @AuthenticationPrincipal User user
    ) {
        return idempotency.execute(idempotencyKey, "campaigns.create", input,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(campaigns.create(input, user.getEmail())));
    }

    /** Editar uma campanha publicada exige a confirmação 2FA: só o serviço sabe se é o caso. */
    @PutMapping("/{id}")
    public CampaignDetail update(
            @PathVariable UUID id,
            @RequestBody CampaignInput input,
            @AuthenticationPrincipal User user,
            HttpServletRequest request
    ) {
        return campaigns.update(id, input, user.getEmail(), () -> reauth.require(request));
    }

    // ───────────────────────────── ações ─────────────────────────────
    // Pede 2FA o que coloca no ar ou tira de forma definitiva. Desativar é livre: é o botão de emergência.

    @PostMapping("/{id}/publish")
    @RequiresReauth
    public CampaignDetail publish(
            @PathVariable UUID id,
            @RequestBody(required = false) VersionRequest body,
            @AuthenticationPrincipal User user
    ) {
        return campaigns.publish(id, version(body), user.getEmail());
    }

    @PostMapping("/{id}/activate-now")
    @RequiresReauth
    public CampaignDetail activateNow(
            @PathVariable UUID id,
            @RequestBody(required = false) VersionRequest body,
            @AuthenticationPrincipal User user
    ) {
        return campaigns.activateNow(id, version(body), user.getEmail());
    }

    @PostMapping("/{id}/disable")
    public CampaignDetail disable(
            @PathVariable UUID id,
            @RequestBody(required = false) VersionRequest body,
            @AuthenticationPrincipal User user
    ) {
        return campaigns.disable(id, version(body), user.getEmail());
    }

    @PostMapping("/{id}/reactivate")
    @RequiresReauth
    public CampaignDetail reactivate(
            @PathVariable UUID id,
            @RequestBody(required = false) VersionRequest body,
            @AuthenticationPrincipal User user
    ) {
        return campaigns.reactivate(id, version(body), user.getEmail());
    }

    @PostMapping("/{id}/archive")
    @RequiresReauth
    public CampaignDetail archive(
            @PathVariable UUID id,
            @RequestBody(required = false) VersionRequest body,
            @AuthenticationPrincipal User user
    ) {
        return campaigns.archive(id, version(body), user.getEmail());
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<?> duplicate(
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            @PathVariable UUID id,
            @AuthenticationPrincipal User user
    ) {
        return idempotency.execute(idempotencyKey, "campaigns.duplicate:" + id, null,
                () -> ResponseEntity.status(HttpStatus.CREATED).body(campaigns.duplicate(id, user.getEmail())));
    }

    @DeleteMapping("/{id}")
    @RequiresReauth
    public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam(required = false) Long version) {
        campaigns.delete(id, version);
        return ResponseEntity.noContent().build();
    }

    private static Long version(VersionRequest body) {
        return body == null ? null : body.version();
    }
}
