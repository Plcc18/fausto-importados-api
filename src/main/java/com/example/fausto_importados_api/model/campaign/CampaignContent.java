package com.example.fausto_importados_api.model.campaign;

import com.example.fausto_importados_api.model.enums.ButtonStyle;
import com.example.fausto_importados_api.model.enums.DecorationType;

import java.util.List;

/**
 * Textos, imagens e decorações de uma campanha, gravados como JSON em {@code campaigns.content}. Os nomes dos campos
 * são os do contrato da API e também os do JSON no banco: renomear um deles exige migrar as linhas existentes.
 *
 * <p>Aqui só há a estrutura; os limites, links e imagens permitidos são conferidos na entrada.
 *
 * @param topBar nulo = a loja mantém a faixa atual
 * @param hero   nulo = a loja mantém o banner principal atual
 */
public record CampaignContent(
        TopBar topBar,
        Hero hero,
        List<PromoImage> promoImages,
        List<DecorationType> decorations
) {

    public record TopBar(String text, String link) {
    }

    /** @param imageMobile nula = usa a de desktop */
    public record Hero(
            String title,
            String subtitle,
            Image imageDesktop,
            Image imageMobile,
            List<Button> buttons
    ) {
    }

    public record Button(String label, String link, ButtonStyle style) {
    }

    /** Só a URL é guardada; o arquivo fica no Cloudinary. */
    public record Image(String url, String alt, Integer width, Integer height) {
    }

    public record PromoImage(Image image, String link) {
    }
}
