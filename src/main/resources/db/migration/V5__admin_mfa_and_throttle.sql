-- 2FA (TOTP) dos administradores, códigos de recuperação e controle de tentativas de login.

-- `active` nunca deve ser nulo (a entidade já o trata como obrigatório)
UPDATE users SET active = TRUE WHERE active IS NULL;
ALTER TABLE users ALTER COLUMN active SET DEFAULT TRUE;
ALTER TABLE users ALTER COLUMN active SET NOT NULL;

ALTER TABLE users
  -- segredo TOTP cifrado com AES-GCM ("v1:<iv>:<texto cifrado>", base64); nunca em claro
  ADD COLUMN totp_secret_enc        VARCHAR(255),
  -- só vale depois que o admin confirmou um código; antes disso o segredo é "pendente"
  ADD COLUMN totp_enabled           BOOLEAN     NOT NULL DEFAULT FALSE,
  -- último passo de 30 s aceito: um código não pode ser usado duas vezes
  ADD COLUMN totp_last_step         BIGINT,
  -- senha temporária (conta criada ou redefinida por outro admin): precisa ser trocada na ativação
  ADD COLUMN must_change_password   BOOLEAN     NOT NULL DEFAULT FALSE,
  -- prazo para concluir o cadastro do 2FA de uma conta recém-criada/redefinida (nulo = sem prazo)
  ADD COLUMN enrollment_expires_at  TIMESTAMPTZ;

-- Códigos de recuperação: só o hash (SHA-256) é guardado; uso único
CREATE TABLE mfa_recovery_codes (
  id          UUID PRIMARY KEY,
  user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  code_hash   VARCHAR(64) NOT NULL UNIQUE,
  created_at  TIMESTAMPTZ NOT NULL,
  used_at     TIMESTAMPTZ
);

CREATE INDEX idx_mfa_recovery_user ON mfa_recovery_codes (user_id);

-- Contadores de falhas de login (por conta e por IP). Ficam no banco para sobreviver a reinícios e
-- valer para qualquer instância da API. A chave é um hash do e-mail ("a:...") ou o IP ("i:...").
CREATE TABLE auth_throttle (
  throttle_key  VARCHAR(80) PRIMARY KEY,
  failures      INTEGER     NOT NULL,
  locked_until  TIMESTAMPTZ,
  updated_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_auth_throttle_updated ON auth_throttle (updated_at);
