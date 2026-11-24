-- Trilha de auditoria: quem fez o quê, quando e de onde.
-- NUNCA grava senhas, tokens, códigos 2FA ou segredos (ver AuditService, que também remove essas chaves de `details`).
CREATE TABLE audit_log (
  id           UUID PRIMARY KEY,
  occurred_at  TIMESTAMPTZ  NOT NULL,
  -- sem FK: o registro precisa sobreviver à exclusão do usuário
  actor_id     UUID,
  actor_email  VARCHAR(150),
  action       VARCHAR(60)  NOT NULL,
  entity_type  VARCHAR(40),
  entity_id    VARCHAR(64),
  -- SUCCESS | FAILURE | DENIED
  outcome      VARCHAR(10)  NOT NULL,
  ip           VARCHAR(64),
  user_agent   VARCHAR(255),
  -- JSON (texto) com detalhes não sensíveis, p.ex. campos alterados {de, para}
  details      TEXT
);

CREATE INDEX idx_audit_log_time   ON audit_log (occurred_at DESC);
CREATE INDEX idx_audit_log_actor  ON audit_log (actor_id, occurred_at DESC);
CREATE INDEX idx_audit_log_entity ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_action ON audit_log (action, occurred_at DESC);
