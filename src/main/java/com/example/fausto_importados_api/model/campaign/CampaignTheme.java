package com.example.fausto_importados_api.model.campaign;

/**
 * Cores de uma campanha, gravadas como JSON em {@code campaigns.theme}. Os nomes dos campos são os do contrato da API
 * e também os do JSON no banco: renomear um deles exige migrar as linhas existentes.
 *
 * <p>Aqui só há a estrutura; as regras (formato {@code #RRGGBB}, campos obrigatórios) são conferidas na entrada.
 *
 * @param dark  paleta escura
 * @param light paleta clara; nula = a loja usa o tema claro padrão
 */
public record CampaignTheme(Palette dark, Palette light) {

    /** De {@code surface} em diante, nulo = valor padrão da loja. */
    public record Palette(
            String background,
            String foreground,
            String primary,
            String primaryForeground,
            String accent,
            String surface,
            String mutedForeground,
            String border,
            String badge,
            String badgeForeground,
            Gold gold
    ) {
    }

    /** Tons do dourado da marca. */
    public record Gold(String hi, String mid, String lo, String deep) {
    }
}
