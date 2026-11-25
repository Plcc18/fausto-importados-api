-- Baldes do rate limit quando não há Redis (REDIS_URL vazio). Compartilhados entre instâncias da API.
-- A chave é "<regra>:<IP | id do usuário | hash do e-mail ou da sessão>"; nunca guarda e-mail em texto.
CREATE TABLE rate_limit_bucket (
  bucket_key  VARCHAR(120)     PRIMARY KEY,
  tokens      DOUBLE PRECISION NOT NULL,
  updated_ms  BIGINT           NOT NULL
);

CREATE INDEX idx_rate_limit_bucket_updated ON rate_limit_bucket (updated_ms);
