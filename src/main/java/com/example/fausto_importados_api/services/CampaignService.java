package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.concurrency.ApiProblemException;
import com.example.fausto_importados_api.concurrency.Transactions;
import com.example.fausto_importados_api.concurrency.Versions;
import com.example.fausto_importados_api.dto.campaign.CampaignDetail;
import com.example.fausto_importados_api.dto.campaign.CampaignFilter;
import com.example.fausto_importados_api.dto.campaign.CampaignInput;
import com.example.fausto_importados_api.dto.campaign.CampaignSummary;
import com.example.fausto_importados_api.mapper.CampaignMapper;
import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.enums.CampaignPhase;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.repository.CampaignRepository;
import com.example.fausto_importados_api.repository.CampaignSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/** Campanhas no painel: listar, detalhar, criar rascunho e editar. */
@Service
public class CampaignService {

    private final CampaignRepository campaigns;
    private final CampaignInputValidator validator;
    private final CampaignConflictGuard conflicts;
    private final CampaignMapper mapper;
    private final Transactions transactions;
    private final Versions versions;
    private final AuditService audit;
    private final Clock clock;

    public CampaignService(
            CampaignRepository campaigns,
            CampaignInputValidator validator,
            CampaignConflictGuard conflicts,
            CampaignMapper mapper,
            Transactions transactions,
            Versions versions,
            AuditService audit,
            Clock clock
    ) {
        this.campaigns = campaigns;
        this.validator = validator;
        this.conflicts = conflicts;
        this.mapper = mapper;
        this.transactions = transactions;
        this.versions = versions;
        this.audit = audit;
        this.clock = clock;
    }

    /** @param page a partir de 0 */
    @Transactional(readOnly = true)
    public Page<CampaignSummary> search(CampaignFilter filter, int page, int size) {
        Instant now = now();
        return campaigns.findAll(CampaignSpecifications.matching(filter, now), PageRequest.of(page, size))
                .map(campaign -> mapper.summary(campaign, now));
    }

    @Transactional(readOnly = true)
    public CampaignDetail get(UUID id) {
        return mapper.detail(campaigns.findDetailById(id).orElseThrow(CampaignService::notFound), now());
    }

    /** Sempre como rascunho. */
    public CampaignDetail create(CampaignInput input, String author) {
        CampaignInput clean = validator.validate(input);
        return transactions.run(() -> {
            Instant now = now();
            Campaign campaign = new Campaign();
            apply(clean, campaign);
            campaign.registerChange(now, author);
            Campaign saved = campaigns.saveAndFlush(campaign);
            record(AuditAction.CAMPAIGN_CREATED, saved);
            return mapper.detail(saved, now);
        });
    }

    /**
     * Substitui o documento inteiro (as coleções ainda não são alteradas por aqui). O status não muda.
     *
     * @param requireReauth chamado se a campanha estiver publicada; lança se falta a confirmação 2FA
     */
    public CampaignDetail update(UUID id, CampaignInput input, String author, Runnable requireReauth) {
        return transactions.run(() -> {
            Campaign campaign = campaigns.findByIdForUpdate(id).orElseThrow(CampaignService::notFound);
            versions.check(input.version(), campaign.getVersion());
            Instant now = now();
            requireEditable(campaign, now);
            boolean published = campaign.getStatus() == CampaignStatus.PUBLISHED;
            if (published) {
                requireReauth.run();
            }

            apply(validator.validate(input), campaign);
            campaign.registerChange(now, author); // sempre: é o que faz a version subir
            if (published) {
                CampaignPublishRequirements.check(campaign, now);
            }
            conflicts.ensureNoConflict(campaign); // antes de gravar; ignora o que não está publicado
            Campaign saved = campaigns.saveAndFlush(campaign);
            record(AuditAction.CAMPAIGN_UPDATED, saved);
            return mapper.detail(saved, now);
        });
    }

    private static void apply(CampaignInput input, Campaign campaign) {
        campaign.setName(input.name());
        campaign.setDescription(input.description());
        campaign.setType(input.type());
        campaign.setPriority(input.priority());
        campaign.setStartsAt(input.startsAt());
        campaign.setEndsAt(input.endsAt());
        campaign.setTheme(input.theme());
        campaign.setContent(input.content());
    }

    private static void requireEditable(Campaign campaign, Instant now) {
        if (campaign.getStatus() == CampaignStatus.ARCHIVED) {
            throw invalidTransition("Uma campanha arquivada não pode ser editada.");
        }
        if (campaign.phaseAt(now) == CampaignPhase.EXPIRED) {
            throw invalidTransition("Uma campanha encerrada não pode ser editada.");
        }
    }

    private void record(AuditAction action, Campaign campaign) {
        Map<String, Object> details = Map.of("status", campaign.getStatus().name(), "version", campaign.getVersion());
        UUID id = campaign.getId();
        transactions.afterCommit(() -> audit.recordSuccess(action, "CAMPAIGN", id, details));
    }

    /** Na precisão do banco. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "not_found", "Campanha não encontrada.");
    }

    private static ApiProblemException invalidTransition(String message) {
        return new ApiProblemException(HttpStatus.CONFLICT, "invalid_transition", message);
    }
}
