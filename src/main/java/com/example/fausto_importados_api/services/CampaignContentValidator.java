package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.dto.validation.FieldChecks;
import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import com.example.fausto_importados_api.model.enums.DecorationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Confere o {@code theme} e o {@code content} de uma campanha (contrato da API, §3 e §4). Tudo o que a loja vai exibir
 * passa por aqui: cores, textos, links, imagens e decorações.
 *
 * <p>Os métodos devolvem uma cópia normalizada (textos sem espaços nas pontas, vazio = nulo, lista ausente = vazia),
 * que é a que deve ser gravada, e acumulam os erros em {@link FieldChecks}. Nada é lançado aqui: chame
 * {@link FieldChecks#throwIfInvalid} antes de usar o resultado.
 *
 * <p>Vale para qualquer escrita, inclusive de rascunho. Por isso não exige o que só é obrigatório para publicar
 * (§6): o tema, a paleta escura e as cores dela, e a imagem de desktop do banner.
 */
@Component
public class CampaignContentValidator {

    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    private static final int MAX_LINK = 300;
    private static final int MAX_IMAGE_URL = 500;
    private static final int MAX_BUTTONS = 2;
    private static final int MAX_PROMO_IMAGES = 6;
    private static final int MAX_DECORATIONS = 3;

    private final String imagePrefix;

    public CampaignContentValidator(@Value("${cloudinary.cloud_name}") String cloudName) {
        // só arquivos enviados à conta: /image/fetch/ serviria uma imagem de qualquer site
        this.imagePrefix = "https://res.cloudinary.com/" + cloudName + "/image/upload/";
    }

    // ───────────────────────────── tema ─────────────────────────────

    public CampaignTheme theme(CampaignTheme theme, FieldChecks checks) {
        if (theme == null) {
            return null;
        }
        return new CampaignTheme(
                palette("theme.dark", theme.dark(), false, checks),
                // a paleta clara é opcional, mas, se vier, precisa estar completa (publicar não a confere)
                palette("theme.light", theme.light(), true, checks));
    }

    private CampaignTheme.Palette palette(String path, CampaignTheme.Palette p, boolean complete, FieldChecks checks) {
        if (p == null) {
            return null;
        }
        return new CampaignTheme.Palette(
                color(path + ".background", p.background(), complete, checks),
                color(path + ".foreground", p.foreground(), complete, checks),
                color(path + ".primary", p.primary(), complete, checks),
                color(path + ".primaryForeground", p.primaryForeground(), complete, checks),
                color(path + ".accent", p.accent(), complete, checks),
                color(path + ".surface", p.surface(), false, checks),
                color(path + ".mutedForeground", p.mutedForeground(), false, checks),
                color(path + ".border", p.border(), false, checks),
                color(path + ".badge", p.badge(), false, checks),
                color(path + ".badgeForeground", p.badgeForeground(), false, checks),
                gold(path + ".gold", p.gold(), checks));
    }

    private CampaignTheme.Gold gold(String path, CampaignTheme.Gold gold, FieldChecks checks) {
        if (gold == null) {
            return null;
        }
        return new CampaignTheme.Gold(
                color(path + ".hi", gold.hi(), true, checks),
                color(path + ".mid", gold.mid(), true, checks),
                color(path + ".lo", gold.lo(), true, checks),
                color(path + ".deep", gold.deep(), true, checks));
    }

    private String color(String field, String value, boolean required, FieldChecks checks) {
        String color = FieldChecks.blankToNull(value);
        if (color == null) {
            if (required) {
                checks.reject(field, "Preencha esta cor.");
            }
        } else if (!COLOR.matcher(color).matches()) {
            checks.reject(field, "Use uma cor no formato #RRGGBB.");
        }
        return color;
    }

    // ───────────────────────────── conteúdo ─────────────────────────────

    public CampaignContent content(CampaignContent content, FieldChecks checks) {
        if (content == null) {
            return null;
        }
        return new CampaignContent(
                topBar("content.topBar", content.topBar(), checks),
                hero("content.hero", content.hero(), checks),
                list("content.promoImages", content.promoImages(), MAX_PROMO_IMAGES, checks,
                        (path, item) -> promoImage(path, item, checks)),
                decorations("content.decorations", content.decorations(), checks));
    }

    private CampaignContent.TopBar topBar(String path, CampaignContent.TopBar topBar, FieldChecks checks) {
        if (topBar == null) {
            return null;
        }
        return new CampaignContent.TopBar(
                checks.requiredText(path + ".text", topBar.text(), 120),
                link(path + ".link", topBar.link(), false, checks));
    }

    private CampaignContent.Hero hero(String path, CampaignContent.Hero hero, FieldChecks checks) {
        if (hero == null) {
            return null;
        }
        return new CampaignContent.Hero(
                checks.requiredText(path + ".title", hero.title(), 80),
                checks.optionalText(path + ".subtitle", hero.subtitle(), 160),
                image(path + ".imageDesktop", hero.imageDesktop(), checks),
                image(path + ".imageMobile", hero.imageMobile(), checks),
                list(path + ".buttons", hero.buttons(), MAX_BUTTONS, checks,
                        (item, button) -> button(item, button, checks)));
    }

    private CampaignContent.Button button(String path, CampaignContent.Button button, FieldChecks checks) {
        if (button.style() == null) {
            checks.reject(path + ".style", "Escolha o estilo do botão.");
        }
        return new CampaignContent.Button(
                checks.requiredText(path + ".label", button.label(), 30),
                link(path + ".link", button.link(), true, checks),
                button.style());
    }

    private CampaignContent.PromoImage promoImage(String path, CampaignContent.PromoImage promo, FieldChecks checks) {
        if (promo.image() == null) {
            checks.reject(path + ".image", "Envie a imagem.");
        }
        return new CampaignContent.PromoImage(
                image(path + ".image", promo.image(), checks),
                link(path + ".link", promo.link(), false, checks));
    }

    private List<DecorationType> decorations(String path, List<DecorationType> decorations, FieldChecks checks) {
        Set<DecorationType> seen = new HashSet<>();
        return list(path, decorations, MAX_DECORATIONS, checks, (item, decoration) -> {
            if (!seen.add(decoration)) {
                checks.reject(item, "Esta decoração já foi escolhida.");
            }
            return decoration;
        });
    }

    /** Lista ausente vira vazia; item nulo é recusado; acima do limite, os itens nem são conferidos. */
    private <T> List<T> list(String path, List<T> items, int max, FieldChecks checks, BiFunction<String, T, T> check) {
        if (items == null) {
            return List.of();
        }
        if (items.size() > max) {
            checks.reject(path, "Use no máximo " + max + " itens.");
            return items;
        }
        List<T> result = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + "[" + i + "]";
            if (items.get(i) == null) {
                checks.reject(itemPath, "Item vazio.");
            } else {
                result.add(check.apply(itemPath, items.get(i)));
            }
        }
        return result;
    }

    // ───────────────────────────── links e imagens ─────────────────────────────

    private String link(String field, String value, boolean required, FieldChecks checks) {
        String link = FieldChecks.blankToNull(value);
        if (link == null) {
            if (required) {
                checks.reject(field, "Informe o link.");
            }
        } else if (link.length() > MAX_LINK) {
            checks.reject(field, "Use no máximo " + MAX_LINK + " caracteres.");
        } else if (!isSafeLink(link)) {
            checks.reject(field, "Use um caminho da loja (começando com /) ou um endereço https://.");
        }
        return link;
    }

    private static boolean isSafeLink(String link) {
        if (hasUnsafeCharacter(link)) {
            return false;
        }
        if (link.startsWith("/")) {
            return !link.startsWith("//"); // //site.com sairia da loja
        }
        return link.startsWith("https://") && hasPlainHost(link);
    }

    /** Endereço com servidor e sem {@code usuario@}, que disfarça o destino. */
    private static boolean hasPlainHost(String link) {
        try {
            String authority = URI.create(link).getRawAuthority();
            return authority != null && !authority.startsWith(":") && !authority.contains("@");
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    // O navegador troca \ por / e descarta tabulações e quebras de linha: "/\site.com" vira "//site.com"
    private static boolean hasUnsafeCharacter(String url) {
        return url.codePoints().anyMatch(c -> {
            int type = Character.getType(c);
            return c == '\\' || c == '<' || c == '>'
                    || Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)
                    || type == Character.FORMAT || type == Character.SURROGATE; // invisíveis
        });
    }

    private CampaignContent.Image image(String path, CampaignContent.Image image, FieldChecks checks) {
        if (image == null) {
            return null;
        }
        return new CampaignContent.Image(
                imageUrl(path + ".url", image.url(), checks),
                checks.requiredText(path + ".alt", image.alt(), 150),
                dimension(path + ".width", image.width(), checks),
                dimension(path + ".height", image.height(), checks));
    }

    private String imageUrl(String field, String value, FieldChecks checks) {
        String url = FieldChecks.blankToNull(value);
        if (url == null) {
            checks.reject(field, "Envie a imagem.");
        } else if (url.length() > MAX_IMAGE_URL) {
            checks.reject(field, "Use no máximo " + MAX_IMAGE_URL + " caracteres.");
        } else if (!isProjectImage(url)) {
            checks.reject(field, "Use uma imagem enviada ao Cloudinary da loja.");
        }
        return url;
    }

    private boolean isProjectImage(String url) {
        if (!url.startsWith(imagePrefix) || url.length() == imagePrefix.length() || hasUnsafeCharacter(url)) {
            return false;
        }
        // "..", mesmo codificado, sairia da pasta da conta depois que o navegador resolvesse o caminho
        String path = url.split("[?#]", 2)[0].toLowerCase(Locale.ROOT);
        if (path.contains("%2e") || path.contains("%2f") || path.contains("%5c")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private Integer dimension(String field, Integer value, FieldChecks checks) {
        if (value != null && value <= 0) {
            checks.reject(field, "Use um número maior que zero.");
        }
        return value;
    }
}
