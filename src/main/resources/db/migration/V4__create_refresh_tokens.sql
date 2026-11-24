-- Refresh tokens (sessões deslizantes com rotação).
-- Só o HASH (SHA-256, hex) do token é armazenado; o valor em claro existe apenas no cookie do browser.
-- Uma "família" (family_id) é uma sessão de login: todos os tokens rotacionados dela compartilham o id.
CREATE TABLE refresh_tokens (
  id                   UUID PRIMARY KEY,
  user_id              UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  family_id            UUID         NOT NULL,
  token_hash           VARCHAR(64)  NOT NULL UNIQUE,
  created_at           TIMESTAMPTZ  NOT NULL,
  last_used_at         TIMESTAMPTZ  NOT NULL,
  -- prazo por inatividade: renovado a cada refresh (nunca passa de absolute_expires_at)
  expires_at           TIMESTAMPTZ  NOT NULL,
  -- limite absoluto da sessão: fixado no login e herdado a cada rotação
  absolute_expires_at  TIMESTAMPTZ  NOT NULL,
  revoked_at           TIMESTAMPTZ,
  -- preenchidos quando o token é trocado por um novo (base da detecção de reuso)
  rotated_at           TIMESTAMPTZ,
  replaced_by_id       UUID,
  user_agent           VARCHAR(255),
  ip                   VARCHAR(64)
);

CREATE INDEX idx_refresh_tokens_family  ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
