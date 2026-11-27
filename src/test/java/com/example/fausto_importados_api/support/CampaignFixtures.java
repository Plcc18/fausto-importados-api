package com.example.fausto_importados_api.support;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.CampaignCollection;
import com.example.fausto_importados_api.model.CampaignProduct;
import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import com.example.fausto_importados_api.model.enums.ButtonStyle;
import com.example.fausto_importados_api.model.enums.CampaignType;
import com.example.fausto_importados_api.model.enums.DecorationType;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Campanhas de exemplo para os testes. */
public final class CampaignFixtures {

    public static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    public static final String AUTHOR = "admin@test.com";

    private CampaignFixtures() {
    }

    /** Rascunho vazio: só o que o banco sempre exige. */
    public static Campaign draft() {
        Campaign campaign = new Campaign();
        campaign.registerChange(NOW, AUTHOR);
        return campaign;
    }

    /** Todos os campos preenchidos, menos os que o contrato permite deixar nulos no tema. */
    public static Campaign complete() {
        Campaign campaign = draft();
        campaign.setName("Black Friday 2026");
        campaign.setDescription("Descrição interna");
        campaign.setType(CampaignType.BLACK_FRIDAY);
        campaign.setPriority(10);
        campaign.setStartsAt(Instant.parse("2026-11-27T03:00:00Z"));
        campaign.setEndsAt(Instant.parse("2026-12-01T03:00:00Z"));
        campaign.setTheme(theme());
        campaign.setContent(content());
        return campaign;
    }

    public static CampaignTheme theme() {
        CampaignTheme.Palette dark = new CampaignTheme.Palette("#0B0B0F", "#F5F1E8", "#C1121F", "#FFFFFF", "#1F1F27",
                null, null, null, "#C1121F", "#FFFFFF",
                new CampaignTheme.Gold("#F7E7A9", "#D4AF37", "#A8832A", "#6E551B"));
        return new CampaignTheme(dark, null);
    }

    public static CampaignContent content() {
        CampaignContent.Image image = new CampaignContent.Image(
                "https://res.cloudinary.com/test/image/upload/v1/campaigns/banner.webp", "Frascos", 1920, 640);
        return new CampaignContent(
                new CampaignContent.TopBar("Black Friday: até 40%", "/?colecao=ofertas"),
                new CampaignContent.Hero("Black Friday Fausto", null, image, null,
                        List.of(new CampaignContent.Button("Ver ofertas", "/?colecao=ofertas", ButtonStyle.PRIMARY))),
                List.of(new CampaignContent.PromoImage(image, null)),
                List.of(DecorationType.SPARKLES, DecorationType.CONFETTI));
    }

    public static CampaignCollection collection(String slug, int position, UUID... productIds) {
        CampaignCollection collection = new CampaignCollection();
        collection.setTitle("Coleção " + slug);
        collection.setSlug(slug);
        collection.setPosition(position);
        collection.replaceProducts(List.of(productIds));
        return collection;
    }

    public static List<String> slugs(Campaign campaign) {
        return campaign.getCollections().stream().map(CampaignCollection::getSlug).toList();
    }

    public static List<UUID> productIds(Campaign campaign, String slug) {
        return bySlug(campaign, slug).getProducts().stream().map(CampaignProduct::productId).toList();
    }

    public static List<Integer> positions(Campaign campaign, String slug) {
        return bySlug(campaign, slug).getProducts().stream()
                .map(CampaignProduct::position).sorted(Comparator.naturalOrder()).toList();
    }

    public static CampaignCollection bySlug(Campaign campaign, String slug) {
        return campaign.getCollections().stream()
                .filter(c -> c.getSlug().equals(slug)).findFirst().orElseThrow();
    }
}
