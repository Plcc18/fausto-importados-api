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
import com.example.fausto_importados_api.dto.campaign.CollectionInput;
import com.example.fausto_importados_api.mapper.CampaignMapper;
import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.CampaignCollection;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Campanhas no painel. Toda escrita sobre uma campanha existente trava a linha, confere a {@code version} e a tabela
 * de transições ({@link CampaignAction}).
 */
@Service
public class CampaignService {

    private final CampaignRepository campaigns;
    private final CampaignInputValidator validator;
    private final CampaignConflictGuard conflicts;
    private final CampaignMapper mapper;
    private final Transactions transactions;
    private final Versions versions;
    private final AuditService audit;
    private final ActiveCampaignCache storeCache;
    private final Clock clock;

    public CampaignService(
            CampaignRepository campaigns,
            CampaignInputValidator validator,
            CampaignConflictGuard conflicts,
            CampaignMapper mapper,
            Transactions transactions,
            Versions versions,
            AuditService audit,
            ActiveCampaignCache storeCache,
            Clock clock
    ) {
        this.campaigns = campaigns;
        this.validator = validator;
        this.conflicts = conflicts;
        this.mapper = mapper;
        this.transactions = transactions;
        this.versions = versions;
        this.audit = audit;
        this.storeCache = storeCache;
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
        CampaignInput clean = validator.validate(input, Set.of());
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
     * Substitui o documento inteiro, inclusive as coleções. O status não muda.
     *
     * @param requireReauth chamado se a campanha estiver publicada; lança se falta a confirmação 2FA
     */
    public CampaignDetail update(UUID id, CampaignInput input, String author, Runnable requireReauth) {
        return transactions.run(() -> {
            Campaign campaign = lock(id, input.version());
            Instant now = now();
            CampaignAction.EDIT.require(campaign, now);
            boolean published = campaign.getStatus() == CampaignStatus.PUBLISHED;
            if (published) {
                requireReauth.run();
            }

            Set<UUID> ownCollections = campaign.getCollections().stream()
                    .map(CampaignCollection::getId)
                    .collect(Collectors.toSet());
            apply(validator.validate(input, ownCollections), campaign);
            campaign.registerChange(now, author); // sempre: é o que faz a version subir
            if (published) {
                CampaignPublishRequirements.check(campaign, now);
            }
            conflicts.ensureNoConflict(campaign); // antes de gravar; ignora o que não está publicado
            campaigns.flush(); // a campanha já está gerenciada: as coleções novas são inseridas aqui e ganham id
            record(AuditAction.CAMPAIGN_UPDATED, campaign);
            return mapper.detail(campaign, now);
        });
    }

    public CampaignDetail publish(UUID id, Long version, String author) {
        return transition(id, version, author, CampaignAction.PUBLISH, AuditAction.CAMPAIGN_PUBLISHED,
                CampaignService::goLive);
    }

    /** Publica (se rascunho) e antecipa o início para agora; um início que já passou é mantido. */
    public CampaignDetail activateNow(UUID id, Long version, String author) {
        return transition(id, version, author, CampaignAction.ACTIVATE_NOW, AuditAction.CAMPAIGN_ACTIVATED,
                (campaign, now) -> {
                    if (campaign.getStartsAt() == null || campaign.getStartsAt().isAfter(now)) {
                        campaign.setStartsAt(now);
                    }
                    if (campaign.getStatus() == CampaignStatus.PUBLISHED) {
                        CampaignPublishRequirements.check(campaign, now);
                    } else {
                        goLive(campaign, now);
                    }
                });
    }

    public CampaignDetail disable(UUID id, Long version, String author) {
        return transition(id, version, author, CampaignAction.DISABLE, AuditAction.CAMPAIGN_DISABLED,
                (campaign, now) -> campaign.setStatus(CampaignStatus.DISABLED));
    }

    public CampaignDetail reactivate(UUID id, Long version, String author) {
        return transition(id, version, author, CampaignAction.REACTIVATE, AuditAction.CAMPAIGN_REACTIVATED,
                CampaignService::goLive);
    }

    public CampaignDetail archive(UUID id, Long version, String author) {
        return transition(id, version, author, CampaignAction.ARCHIVE, AuditAction.CAMPAIGN_ARCHIVED,
                (campaign, now) -> campaign.setStatus(CampaignStatus.ARCHIVED));
    }

    /** Só rascunhos. Apaga as coleções junto; os produtos não são tocados. */
    public void delete(UUID id, Long version) {
        transactions.execute(() -> {
            Campaign campaign = lock(id, version);
            CampaignAction.DELETE.require(campaign, now());
            record(AuditAction.CAMPAIGN_DELETED, campaign);
            campaigns.delete(campaign);
            campaigns.flush();
        });
    }

    /** Cópia como rascunho, sem datas, de uma campanha em qualquer situação. A original não muda. */
    public CampaignDetail duplicate(UUID id, String author) {
        return transactions.run(() -> {
            Instant now = now();
            Campaign copy = campaigns.findDetailById(id).orElseThrow(CampaignService::notFound).copyAsDraft();
            copy.registerChange(now, author);
            Campaign saved = campaigns.saveAndFlush(copy);
            record(AuditAction.CAMPAIGN_DUPLICATED, saved, Map.of("sourceId", id.toString()));
            return mapper.detail(saved, now);
        });
    }

    private CampaignDetail transition(
            UUID id, Long version, String author, CampaignAction action, AuditAction event,
            BiConsumer<Campaign, Instant> change
    ) {
        return transactions.run(() -> {
            Campaign campaign = lock(id, version);
            Instant now = now();
            action.require(campaign, now);
            change.accept(campaign, now);
            campaign.registerChange(now, author);
            conflicts.ensureNoConflict(campaign); // antes de gravar; ignora o que não fica publicado
            Campaign saved = campaigns.saveAndFlush(campaign);
            record(event, saved);
            return mapper.detail(saved, now);
        });
    }

    /** Passa a publicada, se tiver tudo o que isso exige. */
    private static void goLive(Campaign campaign, Instant now) {
        campaign.setStatus(CampaignStatus.PUBLISHED);
        campaign.setPublishedAt(now);
        CampaignPublishRequirements.check(campaign, now);
    }

    private Campaign lock(UUID id, Long expectedVersion) {
        Campaign campaign = campaigns.findByIdForUpdate(id).orElseThrow(CampaignService::notFound);
        versions.check(expectedVersion, campaign.getVersion());
        return campaign;
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
        applyCollections(input.collections(), campaign);
    }

    /** Coleção com id é atualizada, sem id é criada e a que não veio é apagada. Os produtos não são tocados. */
    private static void applyCollections(List<CollectionInput> collections, Campaign campaign) {
        Map<UUID, CampaignCollection> current = campaign.getCollections().stream()
                .filter(c -> c.getId() != null)
                .collect(Collectors.toMap(CampaignCollection::getId, Function.identity()));
        Set<UUID> kept = collections.stream().map(CollectionInput::id).collect(Collectors.toSet());
        campaign.getCollections().removeIf(c -> !kept.contains(c.getId()));

        for (CollectionInput input : collections) {
            CampaignCollection collection = input.id() == null ? new CampaignCollection() : current.get(input.id());
            collection.setTitle(input.title());
            collection.setSlug(input.slug());
            collection.setPosition(input.position());
            collection.replaceProducts(input.products().stream().map(CollectionInput.Product::productId).toList());
            if (input.id() == null) {
                campaign.addCollection(collection);
            }
        }
    }

    private void record(AuditAction action, Campaign campaign) {
        record(action, campaign, Map.of());
    }

    private void record(AuditAction action, Campaign campaign, Map<String, ?> extra) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", campaign.getName()); // o registro de uma exclusão precisa dizer o que foi excluído
        details.put("status", campaign.getStatus().name());
        details.put("version", campaign.getVersion());
        details.putAll(extra);
        UUID id = campaign.getId();
        // toda escrita passa por aqui: depois do commit, a loja volta a ler o banco
        transactions.afterCommit(storeCache::invalidate);
        transactions.afterCommit(() -> audit.recordSuccess(action, "CAMPAIGN", id, details));
    }

    /** Na precisão do banco. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiProblemException notFound() {
        return new ApiProblemException(HttpStatus.NOT_FOUND, "not_found", "Campanha não encontrada.");
    }
}
