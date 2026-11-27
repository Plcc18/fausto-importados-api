-- Campanhas sazonais (contrato em fausto-importados-web/docs/campanhas-contrato.md).
-- Só cria tabelas novas: nada em products, orders ou users muda. Uma campanha nunca altera preços de produtos.
-- Os campos de desconto (segunda etapa) entram numa migration própria.

CREATE TABLE campaigns (
  id            UUID PRIMARY KEY,
  -- nome, tipo, datas e tema podem faltar enquanto a campanha não é publicada (ver chk_campaigns_published)
  name          VARCHAR(80),
  description   VARCHAR(500),
  type          VARCHAR(30),
  -- só o que o admin decidiu; agendada, ativa e expirada são calculadas pelas datas
  status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
  -- início inclusivo, fim exclusivo
  starts_at     TIMESTAMPTZ,
  ends_at       TIMESTAMPTZ,
  priority      INTEGER      NOT NULL DEFAULT 0,
  -- imagens aparecem no JSON apenas como URL
  theme         JSONB,
  content       JSONB,
  version       BIGINT       NOT NULL DEFAULT 0,
  created_at    TIMESTAMPTZ  NOT NULL,
  updated_at    TIMESTAMPTZ  NOT NULL,
  published_at  TIMESTAMPTZ,
  -- e-mail de quem criou e de quem alterou por último; sem FK: a campanha sobrevive à exclusão do usuário
  created_by    VARCHAR(150) NOT NULL,
  updated_by    VARCHAR(150) NOT NULL,

  CONSTRAINT chk_campaigns_status   CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED', 'ARCHIVED')),
  CONSTRAINT chk_campaigns_type     CHECK (type IN ('BLACK_FRIDAY', 'CHRISTMAS', 'EASTER', 'VALENTINES_DAY',
                                                    'MOTHERS_DAY', 'FATHERS_DAY', 'CONSUMER_DAY',
                                                    'STORE_ANNIVERSARY', 'CUSTOM')),
  CONSTRAINT chk_campaigns_period   CHECK (ends_at > starts_at),
  CONSTRAINT chk_campaigns_priority CHECK (priority BETWEEN 0 AND 100),
  CONSTRAINT chk_campaigns_published CHECK (
    status <> 'PUBLISHED'
    OR (name IS NOT NULL AND type IS NOT NULL AND starts_at IS NOT NULL AND ends_at IS NOT NULL AND theme IS NOT NULL))
);

-- Listagem do painel (ordem por início, filtro por status)
CREATE INDEX idx_campaigns_status_start ON campaigns (status, starts_at DESC);
CREATE INDEX idx_campaigns_start        ON campaigns (starts_at DESC);

-- Campanha ativa e conflitos de período: só as publicadas interessam
CREATE INDEX idx_campaigns_published_period ON campaigns (ends_at, starts_at) WHERE status = 'PUBLISHED';

CREATE TABLE campaign_collections (
  id           UUID PRIMARY KEY,
  campaign_id  UUID        NOT NULL,
  title        VARCHAR(60) NOT NULL,
  -- identificador do link /?colecao=<slug>
  slug         VARCHAR(40) NOT NULL,
  position     INTEGER     NOT NULL,

  CONSTRAINT fk_campaign_collections_campaign FOREIGN KEY (campaign_id) REFERENCES campaigns (id) ON DELETE CASCADE,
  -- conferida no commit: a edição substitui as coleções de uma vez e pode trocar ou reaproveitar um identificador
  CONSTRAINT uq_campaign_collections_slug UNIQUE (campaign_id, slug) DEFERRABLE INITIALLY DEFERRED,
  CONSTRAINT chk_campaign_collections_position CHECK (position >= 0)
);

CREATE TABLE campaign_collection_products (
  collection_id  UUID    NOT NULL,
  product_id     UUID    NOT NULL,
  position       INTEGER NOT NULL,

  CONSTRAINT pk_campaign_collection_products PRIMARY KEY (collection_id, product_id),
  CONSTRAINT fk_campaign_collection_products_collection
    FOREIGN KEY (collection_id) REFERENCES campaign_collections (id) ON DELETE CASCADE,
  -- sem cascata: produtos têm exclusão lógica; apagar um que participa de campanha é erro
  CONSTRAINT fk_campaign_collection_products_product FOREIGN KEY (product_id) REFERENCES products (id),
  CONSTRAINT chk_campaign_collection_products_position CHECK (position >= 0)
);

-- A chave primária já atende a busca por coleção; este cobre a FK do produto
CREATE INDEX idx_campaign_collection_products_product ON campaign_collection_products (product_id);
