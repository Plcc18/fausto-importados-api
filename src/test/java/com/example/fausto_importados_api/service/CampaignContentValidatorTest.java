package com.example.fausto_importados_api.service;

import com.example.fausto_importados_api.dto.validation.FieldChecks;
import com.example.fausto_importados_api.dto.validation.InvalidField;
import com.example.fausto_importados_api.exception.FieldValidationException;
import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignContent.Button;
import com.example.fausto_importados_api.model.campaign.CampaignContent.Hero;
import com.example.fausto_importados_api.model.campaign.CampaignContent.Image;
import com.example.fausto_importados_api.model.campaign.CampaignContent.PromoImage;
import com.example.fausto_importados_api.model.campaign.CampaignContent.TopBar;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import com.example.fausto_importados_api.model.campaign.CampaignTheme.Gold;
import com.example.fausto_importados_api.model.campaign.CampaignTheme.Palette;
import com.example.fausto_importados_api.model.enums.ButtonStyle;
import com.example.fausto_importados_api.model.enums.DecorationType;
import com.example.fausto_importados_api.services.CampaignContentValidator;
import com.example.fausto_importados_api.support.CampaignFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampaignContentValidatorTest {

    private static final String IMAGE_URL = "https://res.cloudinary.com/test/image/upload/v1/campaigns/banner.webp";
    private static final Image IMAGE = new Image(IMAGE_URL, "Frascos", 800, 800);

    private final CampaignContentValidator validator = new CampaignContentValidator("test");

    // ───────────────────────────── entradas válidas e rascunho ─────────────────────────────

    @Test
    void aCompleteThemeAndContentPassUnchanged() {
        FieldChecks checks = new FieldChecks();

        CampaignTheme theme = validator.theme(CampaignFixtures.theme(), checks);
        CampaignContent content = validator.content(CampaignFixtures.content(), checks);

        checks.throwIfInvalid();
        assertThat(theme).isEqualTo(CampaignFixtures.theme());
        assertThat(content).isEqualTo(CampaignFixtures.content());
    }

    @Test
    void aDraftMayHaveNoThemeNorContent() {
        FieldChecks checks = new FieldChecks();

        assertThat(validator.theme(null, checks)).isNull();
        assertThat(validator.content(null, checks)).isNull();
        checks.throwIfInvalid();
    }

    @Test
    void aDraftMayLeaveOutWhatIsOnlyRequiredToPublish() {
        assertThat(themeErrors(new CampaignTheme(null, null))).isEmpty();
        assertThat(themeErrors(new CampaignTheme(palette(null, null), null))).isEmpty();
        assertThat(errors(hero(new Hero("Título", null, null, null, null)))).isEmpty();
    }

    @Test
    void valuesAreNormalizedBeforeBeingStored() {
        FieldChecks checks = new FieldChecks();

        CampaignContent content = validator.content(new CampaignContent(
                new TopBar("  Oferta  ", "   "),
                new Hero(" Natal ", "   ", null, null, null),
                null, null), checks);
        CampaignTheme theme = validator.theme(new CampaignTheme(palette(" #aBcDeF ", "  "), null), checks);

        checks.throwIfInvalid();
        assertThat(content.topBar()).isEqualTo(new TopBar("Oferta", null));
        assertThat(content.hero()).isEqualTo(new Hero("Natal", null, null, null, List.of()));
        assertThat(content.promoImages()).isEmpty();
        assertThat(content.decorations()).isEmpty();
        assertThat(theme.dark().background()).isEqualTo("#aBcDeF");
        assertThat(theme.dark().foreground()).isNull();
    }

    // ───────────────────────────── cores ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"#FFF", "#GGGGGG", "#1234567", "123456", "red", "rgb(0,0,0)", "var(--gold-hi)",
            "#123456;", "#123456}body{display:none}", "url(https://site.com/a.png)"})
    void onlySixDigitHexColorsAreAccepted(String color) {
        assertThat(themeErrors(new CampaignTheme(palette(color, null), null)))
                .containsExactly(new InvalidField("theme.dark.background", "Use uma cor no formato #RRGGBB."));
    }

    @Test
    void everyColorIsChecked() {
        Palette bad = new Palette("x", "x", "x", "x", "x", "x", "x", "x", "x", "x", new Gold("x", "x", "x", "x"));

        assertThat(fields(themeErrors(new CampaignTheme(bad, null)))).containsExactly(
                "theme.dark.background", "theme.dark.foreground", "theme.dark.primary",
                "theme.dark.primaryForeground", "theme.dark.accent", "theme.dark.surface",
                "theme.dark.mutedForeground", "theme.dark.border", "theme.dark.badge", "theme.dark.badgeForeground",
                "theme.dark.gold.hi", "theme.dark.gold.mid", "theme.dark.gold.lo", "theme.dark.gold.deep");
    }

    @Test
    void theLightPaletteMustBeCompleteWhenPresent() {
        Palette empty = new Palette(null, null, null, null, null, null, null, null, null, null, null);

        assertThat(fields(themeErrors(new CampaignTheme(null, empty)))).containsExactly(
                "theme.light.background", "theme.light.foreground", "theme.light.primary",
                "theme.light.primaryForeground", "theme.light.accent");
    }

    @Test
    void theGoldTonesComeAllTogether() {
        Palette dark = new Palette(null, null, null, null, null, null, null, null, null, null,
                new Gold("#F7E7A9", null, " ", null));

        assertThat(fields(themeErrors(new CampaignTheme(dark, null)))).containsExactly(
                "theme.dark.gold.mid", "theme.dark.gold.lo", "theme.dark.gold.deep");
    }

    // ───────────────────────────── textos ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"<b>Oferta</b>", "<script>alert(1)</script>", "10 > 5", "a < b"})
    void htmlIsRefused(String text) {
        assertThat(errors(topBar(text, null)))
                .containsExactly(new InvalidField("content.topBar.text",
                        "Use só texto: os sinais < e > não são aceitos."));
    }

    // quebra de linha, nulo, separador de linha e metade solta de um par UTF-16
    @ParameterizedTest
    @ValueSource(strings = {"linha\nnova", "com\ttab", "nulo\u0000", "separa dor", "metade\uD83D solta"})
    void controlCharactersAreRefused(String text) {
        assertThat(fields(errors(topBar(text, null)))).containsExactly("content.topBar.text");
    }

    @Test
    void eachTextHasItsOwnLimit() {
        assertThat(errors(withTextsOfLength(0))).isEmpty();
        assertThat(fields(errors(withTextsOfLength(1)))).containsExactly(
                "content.topBar.text", "content.hero.title", "content.hero.subtitle",
                "content.hero.imageDesktop.alt", "content.hero.buttons[0].label");
    }

    @Test
    void anEmojiCountsAsOneCharacter() {
        assertThat(errors(topBar("🎄".repeat(120), null))).isEmpty();
        assertThat(errors(topBar("🎄".repeat(121), null)))
                .containsExactly(new InvalidField("content.topBar.text", "Use no máximo 120 caracteres."));
    }

    @Test
    void requiredTextsCannotBeBlank() {
        CampaignContent content = new CampaignContent(
                new TopBar("  ", null),
                new Hero(null, null, new Image(null, " ", null, null), null,
                        List.of(new Button(null, null, null))),
                List.of(new PromoImage(null, null)),
                null);

        assertThat(fields(errors(content))).containsExactlyInAnyOrder(
                "content.topBar.text", "content.hero.title",
                "content.hero.imageDesktop.url", "content.hero.imageDesktop.alt",
                "content.hero.buttons[0].label", "content.hero.buttons[0].link", "content.hero.buttons[0].style",
                "content.promoImages[0].image");
    }

    // ───────────────────────────── links ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"/", "/?colecao=ofertas", "/#colecao", "/produtos?x=1&y=2",
            "https://wa.me/5585999999999?text=Ol%C3%A1", "https://www.instagram.com/fausto.importados/",
            "https://exemplo.com:8443/a"})
    void internalPathsAndHttpsAddressesAreAccepted(String link) {
        assertThat(errors(topBar("Oferta", link))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,oi", "mailto:a@site.com", "ftp://site.com",
            "http://site.com", "HTTPS://site.com", "https:site.com", "site.com", "?colecao=ofertas", "#topo",
            "//site.com", "/\\site.com", "\\\\site.com", "/\t/site.com", "/\n/site.com", "/com espaco",
            "/invisi​vel", "/nao separavel", "/<script>",
            "https://", "https:///caminho", "https://:443", "https://loja.com@site.com", "https://a{b}.com"})
    void otherLinksAreRefused(String link) {
        assertThat(errors(topBar("Oferta", link))).containsExactly(new InvalidField("content.topBar.link",
                "Use um caminho da loja (começando com /) ou um endereço https://."));
    }

    @Test
    void aLinkHasAtMost300Characters() {
        assertThat(errors(topBar("Oferta", "/" + "a".repeat(299)))).isEmpty();
        assertThat(errors(topBar("Oferta", "/" + "a".repeat(300))))
                .containsExactly(new InvalidField("content.topBar.link", "Use no máximo 300 caracteres."));
    }

    @Test
    void theLinksOfButtonsAndPromoImagesAreCheckedToo() {
        CampaignContent content = new CampaignContent(null,
                new Hero("Título", null, null, null,
                        List.of(new Button("Ver", "javascript:alert(1)", ButtonStyle.PRIMARY))),
                List.of(new PromoImage(IMAGE, "//site.com")),
                null);

        assertThat(fields(errors(content)))
                .containsExactly("content.hero.buttons[0].link", "content.promoImages[0].link");
    }

    // ───────────────────────────── imagens ─────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {IMAGE_URL,
            "https://res.cloudinary.com/test/image/upload/f_auto,q_auto,w_1920/v1/campaigns/banner.v2.webp",
            IMAGE_URL + "?_a=BAM"})
    void imagesUploadedToTheStoreAccountAreAccepted(String url) {
        assertThat(errors(promo(new Image(url, "Frascos", null, null)))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://res.cloudinary.com/outra/image/upload/v1/a.webp",
            "https://res.cloudinary.com/test-outra/image/upload/v1/a.webp",
            "https://res.cloudinary.com.site.com/test/image/upload/v1/a.webp",
            "https://site.com/https://res.cloudinary.com/test/image/upload/v1/a.webp",
            "http://res.cloudinary.com/test/image/upload/v1/a.webp",
            "https://res.cloudinary.com/test/image/fetch/https://site.com/a.png",
            "https://res.cloudinary.com/test/image/upload/",
            "https://res.cloudinary.com/test/image/upload/../../../outra/image/upload/a.webp",
            "https://res.cloudinary.com/test/image/upload/%2e%2e/%2E%2E/outra/a.webp",
            "https://res.cloudinary.com/test/image/upload/..%2F..%2Foutra/a.webp",
            "https://res.cloudinary.com/test/image/upload/..\\..\\outra/a.webp",
            "https://res.cloudinary.com/test/image/upload/com espaco.webp",
            "/banner.webp", "javascript:alert(1)"})
    void otherImageAddressesAreRefused(String url) {
        assertThat(errors(promo(new Image(url, "Frascos", null, null))))
                .containsExactly(new InvalidField("content.promoImages[0].image.url",
                        "Use uma imagem enviada ao Cloudinary da loja."));
    }

    @Test
    void imageDimensionsMustBePositive() {
        assertThat(fields(errors(promo(new Image(IMAGE_URL, "Frascos", 0, -1)))))
                .containsExactly("content.promoImages[0].image.width", "content.promoImages[0].image.height");
    }

    // ───────────────────────────── listas ─────────────────────────────

    @Test
    void listsAcceptUpToTheirLimit() {
        assertThat(errors(lists(2, 6,
                List.of(DecorationType.SNOW, DecorationType.HEARTS, DecorationType.STARS)))).isEmpty();
    }

    @Test
    void listsAboveTheLimitAreRefused() {
        assertThat(errors(lists(3, 7, Arrays.asList(DecorationType.values()).subList(0, 4)))).containsExactly(
                new InvalidField("content.hero.buttons", "Use no máximo 2 itens."),
                new InvalidField("content.promoImages", "Use no máximo 6 itens."),
                new InvalidField("content.decorations", "Use no máximo 3 itens."));
    }

    @Test
    void repeatedDecorationsAndEmptyItemsAreRefused() {
        CampaignContent content = new CampaignContent(null,
                new Hero("Título", null, null, null, Collections.singletonList(null)),
                Collections.singletonList(null),
                Arrays.asList(DecorationType.SNOW, DecorationType.SNOW, null));

        assertThat(errors(content)).containsExactlyInAnyOrder(
                new InvalidField("content.hero.buttons[0]", "Item vazio."),
                new InvalidField("content.promoImages[0]", "Item vazio."),
                new InvalidField("content.decorations[1]", "Esta decoração já foi escolhida."),
                new InvalidField("content.decorations[2]", "Item vazio."));
    }

    // ───────────────────────────── resposta ─────────────────────────────

    @Test
    void allTheErrorsAreThrownTogether() {
        FieldChecks checks = new FieldChecks();
        validator.theme(new CampaignTheme(palette("azul", null), null), checks);
        validator.content(topBar("<b>", "javascript:alert(1)"), checks);

        assertThatThrownBy(checks::throwIfInvalid)
                .isInstanceOfSatisfying(FieldValidationException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo("validation_failed");
                    assertThat(fields(e.fields())).containsExactly(
                            "theme.dark.background", "content.topBar.text", "content.topBar.link");
                });
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private List<InvalidField> themeErrors(CampaignTheme theme) {
        return collect(checks -> validator.theme(theme, checks));
    }

    private List<InvalidField> errors(CampaignContent content) {
        return collect(checks -> validator.content(content, checks));
    }

    private static List<InvalidField> collect(Function<FieldChecks, Object> validation) {
        FieldChecks checks = new FieldChecks();
        validation.apply(checks);
        try {
            checks.throwIfInvalid();
            return List.of();
        } catch (FieldValidationException e) {
            return e.fields();
        }
    }

    private static List<String> fields(List<InvalidField> errors) {
        return errors.stream().map(InvalidField::field).toList();
    }

    private static Palette palette(String background, String foreground) {
        return new Palette(background, foreground, null, null, null, null, null, null, null, null, null);
    }

    private static CampaignContent topBar(String text, String link) {
        return new CampaignContent(new TopBar(text, link), null, null, null);
    }

    private static CampaignContent hero(Hero hero) {
        return new CampaignContent(null, hero, null, null);
    }

    private static CampaignContent promo(Image image) {
        return new CampaignContent(null, null, List.of(new PromoImage(image, null)), null);
    }

    /** Todos os textos no limite de cada um, mais {@code extra} caracteres. */
    private static CampaignContent withTextsOfLength(int extra) {
        Function<Integer, String> text = limit -> "a".repeat(limit + extra);
        return new CampaignContent(
                new TopBar(text.apply(120), null),
                new Hero(text.apply(80), text.apply(160), new Image(IMAGE_URL, text.apply(150), null, null), null,
                        List.of(new Button(text.apply(30), "/", ButtonStyle.SECONDARY))),
                null, null);
    }

    private static CampaignContent lists(int buttons, int promos, List<DecorationType> decorations) {
        List<Button> buttonList = new ArrayList<>(Collections.nCopies(buttons,
                new Button("Ver", "/", ButtonStyle.PRIMARY)));
        List<PromoImage> promoList = new ArrayList<>(Collections.nCopies(promos, new PromoImage(IMAGE, null)));
        return new CampaignContent(null, new Hero("Título", null, null, null, buttonList), promoList, decorations);
    }
}
