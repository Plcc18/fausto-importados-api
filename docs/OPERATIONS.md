# Operação — administradores, 2FA, auditoria e backup

Guia para quem opera o Fausto Importados (2 ou 3 administradores). Tudo aqui vale para a API (Render) e o PostgreSQL.
O front (Vercel) só mostra telas: **quem decide é sempre a API**.

> Convenções dos exemplos: `API` é a URL da API (ex.: `https://fausto-importados-api.onrender.com`). Os comandos usam
> `curl` e `jq` (no Windows, rode no Git Bash). Nunca cole senhas ou códigos em histórico compartilhado.

## Sumário

1. [Variáveis de ambiente](#1-variáveis-de-ambiente)
2. [Primeiro administrador](#2-primeiro-administrador)
3. [Chamar a API como administrador](#3-chamar-a-api-como-administrador)
4. [Criar um administrador](#4-criar-um-administrador)
5. [Desativar, reativar e excluir](#5-desativar-reativar-e-excluir)
6. [Perdeu o celular, os códigos ou a senha](#6-perdeu-o-celular-os-códigos-ou-a-senha)
7. [Emergência: nenhum administrador consegue entrar](#7-emergência-nenhum-administrador-consegue-entrar)
8. [Conta ou IP bloqueado](#8-conta-ou-ip-bloqueado)
9. [A chave `TOTP_ENCRYPTION_KEY`](#9-a-chave-totp_encryption_key)
10. [Auditoria](#10-auditoria)
11. [Backup e restauração](#11-backup-e-restauração)

---

## 1. Variáveis de ambiente

| Variável | Onde | Para quê |
|---|---|---|
| `TOTP_ENCRYPTION_KEY` | Render | **Obrigatória.** Chave AES-256 (base64, 32 bytes) que cifra os segredos do 2FA. A API não sobe sem ela. |
| `JWT_SECRET` | Render | Assina os tokens de acesso. |
| `FRONTEND_URL` | Render | Origem(ns) do front (CORS e verificação de `Origin` no refresh). |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | Render | **Só** para criar o primeiro admin. Remova depois do primeiro login. |
| `SPRING_PROFILES_ACTIVE=prod` | Render | Perfil de produção (`application-prod.yml`: logs enxutos, escuta em `0.0.0.0`). Recomendado. Os cookies de sessão já são `Secure`/`SameSite=Strict` por padrão e o SQL/DEBUG de segurança só aparecem no perfil `dev`. |
| `BACKUP_DATABASE_URL`, `BACKUP_GPG_PUBLIC_KEY` | GitHub (secrets) | Só para o backup diário (seção 11). |

Gerar a chave do 2FA: `openssl rand -base64 32`. **Guarde uma cópia fora do Render** (gerenciador de senhas).

## 2. Primeiro administrador

1. No Render defina `ADMIN_EMAIL`, `ADMIN_PASSWORD` (senha forte) e `TOTP_ENCRYPTION_KEY`, e faça o deploy.
   A API cria o admin **só se ainda não existir nenhum** (nos próximos deploys nada acontece).
2. Abra `https://<seu-front>/entrar`, informe e-mail e senha. Como a conta ainda não tem 2FA, a tela mostra o QR code:
   escaneie com Google Authenticator, Authy, 1Password etc., digite o código e **guarde os 10 códigos de recuperação**
   (aparecem uma única vez).
3. Remova `ADMIN_EMAIL` e `ADMIN_PASSWORD` do Render.

## 3. Chamar a API como administrador

Ainda não há tela para gerir administradores; use a API. Cada alteração exige **login com 2FA** e uma **reautenticação**
(um código novo — um código TOTP vale uma vez, então espere o aplicativo trocar de código entre um passo e outro).

```bash
API=https://fausto-importados-api.onrender.com
# Se PROXY_REQUIRE_FOR_AUTH=true na API, /auth/* só responde com o segredo do proxy (senão: 404):
# PS="-H X-Proxy-Secret:$PROXY_SHARED_SECRET"  e acrescente $PS aos curl de /auth abaixo

# 1) senha  -> devolve um "challenge"
CH=$(curl -s "$API/auth/login" -H 'Content-Type: application/json' \
      -d '{"email":"voce@exemplo.com","password":"SUA_SENHA"}' | jq -r .challenge)

# 2) código do aplicativo -> access token
TOKEN=$(curl -s "$API/auth/login/mfa" -H 'Content-Type: application/json' \
      -d "{\"challenge\":\"$CH\",\"code\":\"123456\"}" | jq -r .accessToken)

# 3) reautenticar (espere o PRÓXIMO código do aplicativo) -> prova de 5 minutos
PROOF=$(curl -s "$API/auth/reauth" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
      -d '{"code":"654321"}' | jq -r .reauthToken)
```

Daí em diante, as chamadas sensíveis levam `-H "Authorization: Bearer $TOKEN" -H "X-Reauth: $PROOF"`.
O access token vale 15 minutos; a prova, 5. Sem a prova a resposta é `403` com `"code": "reauth_required"`.
Erro `429` = muitas tentativas (veja a seção 8).

## 4. Criar um administrador

Cada pessoa tem a **própria** conta. Ninguém define a senha de outra pessoa: o servidor gera uma senha temporária.

```bash
curl -s -X POST "$API/api/user" -H "Authorization: Bearer $TOKEN" -H "X-Reauth: $PROOF" \
  -H 'Content-Type: application/json' -d '{"email":"nova.pessoa@exemplo.com"}' | jq
# => { "id": "...", "email": "...", "temporaryPassword": "Xk7...", "enrollmentExpiresAt": "..." }
```

- A `temporaryPassword` **aparece só nesta resposta**. Entregue por um canal seguro (pessoalmente, mensagem que apaga,
  gerenciador de senhas compartilhado) — nunca junto com o e-mail por um canal inseguro.
- A pessoa entra em `/entrar` com a senha temporária **em até 24 h**, cadastra o autenticador, escolhe a senha definitiva
  (mínimo de 12 caracteres) e guarda os códigos de recuperação.
- Se as 24 h passarem sem o cadastro, a conta fica inutilizável: gere uma nova senha com
  [`reset-credentials`](#6-perdeu-o-celular-os-códigos-ou-a-senha).
- Confira depois em `GET /api/user` (`mfaEnabled: true`) que a pessoa concluiu o cadastro.

## 5. Desativar, reativar e excluir

```bash
ID=<uuid da conta>   # veja em GET /api/user
# desativar: encerra TODAS as sessões na hora e bloqueia o login
curl -s -X PATCH "$API/api/user/$ID/active" -H "Authorization: Bearer $TOKEN" -H "X-Reauth: $PROOF" \
  -H 'Content-Type: application/json' -d '{"active":false}'
# reativar: {"active":true}
# excluir (prefira desativar; some junto sessões e códigos de recuperação)
curl -s -X DELETE "$API/api/user/$ID" -H "Authorization: Bearer $TOKEN" -H "X-Reauth: $PROOF"
```

**Quando alguém sai da equipe:** desative a conta no mesmo dia. Trocar a senha dela não é necessário se a conta fica
desativada. Regras de proteção: você não consegue desativar/excluir a **própria** conta e o **último admin ativo** nunca
pode ser desativado ou excluído (`409`).

## 6. Perdeu o celular, os códigos ou a senha

**Situação mais comum — outro administrador resolve** (a pessoa perdeu o aparelho, ou esqueceu a senha):

```bash
curl -s -X POST "$API/api/user/$ID/reset-credentials" -H "Authorization: Bearer $TOKEN" -H "X-Reauth: $PROOF" | jq
# => nova temporaryPassword (mostrada uma vez) + prazo de 24 h
```

Isso apaga o 2FA e os códigos de recuperação da conta, **encerra todas as sessões** dela e destrava a conta. A pessoa
refaz o cadastro como no primeiro acesso (seção 4). Você não consegue redefinir a própria conta por esta rota.

**Ainda tenho acesso, só quero novos códigos de recuperação** (ex.: usei vários):

```bash
curl -s -X POST "$API/auth/mfa/recovery-codes" -H "Authorization: Bearer $TOKEN" -H "X-Reauth: $PROOF" | jq
```

Os códigos antigos deixam de valer. **Trocar a própria senha:** `POST /auth/password` com
`{"currentPassword":"...","newPassword":"..."}` (exige reautenticação; as outras sessões são encerradas).

## 7. Emergência: nenhum administrador consegue entrar

Só se **todos** perderam o 2FA e os códigos (ou há um único admin). Faça direto no banco — é o único caminho sem outro
admin. Use `psql` com a URL externa do banco (o `\set` abaixo é do `psql`; em outro cliente SQL, troque `:'email'` pelo
e-mail entre aspas simples). **Aja rápido:** enquanto a conta está sem 2FA, quem souber a senha consegue cadastrar o
autenticador dela — por isso a janela é de 1 hora.

```sql
-- troque pelo e-mail da conta
\set email 'voce@exemplo.com'

BEGIN;
UPDATE users
   SET totp_enabled = false, totp_secret_enc = NULL, totp_last_step = NULL,
       enrollment_expires_at = now() + interval '1 hour'      -- janela curta para refazer o 2FA
 WHERE lower(email) = lower(:'email');

DELETE FROM mfa_recovery_codes WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower(:'email'));
DELETE FROM refresh_tokens     WHERE user_id = (SELECT id FROM users WHERE lower(email) = lower(:'email'));
DELETE FROM auth_throttle;     -- destrava contas e IPs
COMMIT;
```

Depois entre em `/entrar` com a senha atual: a tela leva ao cadastro do autenticador. Se também esqueceu a senha, gere um
hash BCrypt e inclua `password = '<hash>', must_change_password = true` no `UPDATE`:

```bash
# Apache htpasswd (ou qualquer gerador BCrypt); o prefixo $2y$ é aceito pelo Spring
htpasswd -bnBC 10 "" 'SenhaTemporariaLonga123' | tr -d ':\n'
```

Depois do cadastro, **confira a auditoria** (seção 10) para ver quem entrou durante a janela.

## 8. Conta ou IP bloqueado

O login bloqueia temporariamente depois de falhas (5 por conta → 1 min, dobrando até 30 min; 20 por IP). Falhas de senha
e de código 2FA somam. O bloqueio passa sozinho. Para destravar agora (qualquer admin com acesso ao banco):

```sql
DELETE FROM auth_throttle;                                  -- tudo
-- ou só uma conta: o outro admin usa reset-credentials (seção 6), que também destrava
```

> O IP de quem entra por `/auth/*` chega pela função de borda da Vercel (`X-Client-IP` + `X-Proxy-Secret`, ver README,
> "Rate limiting"). Sem o segredo configurado nos dois lados, a API só enxerga o endereço de saída da Vercel (o mesmo para
> todos): aí o controle principal é **por conta**. Um atacante pode travar uma conta por até 30 min; nunca consegue
> entrar nela por isso.

## 9. A chave `TOTP_ENCRYPTION_KEY`

Ela cifra (AES-256-GCM) os segredos do autenticador guardados no banco. Quem vazar o banco **sem** a chave não consegue
gerar códigos.

- **Perdeu a chave** (ou trocou o valor no Render): os segredos ficam ilegíveis e o login com 2FA passa a falhar
  (erro 500) para **todos**. Defina uma chave nova no Render e rode o SQL abaixo; cada pessoa entra com a senha atual e
  refaz o cadastro do autenticador (a janela de 1 hora vale para todas):

  ```sql
  BEGIN;
  UPDATE users SET totp_enabled = false, totp_secret_enc = NULL, totp_last_step = NULL,
                   enrollment_expires_at = now() + interval '1 hour' WHERE role = 'ADMIN';
  DELETE FROM mfa_recovery_codes;
  DELETE FROM refresh_tokens;
  COMMIT;
  ```
- **Rodízio planejado:** não há troca "a quente". É o mesmo procedimento: troque a variável e rode o SQL acima.
- Guarde a chave **fora** do Render e **separada** dos backups do banco.

## 10. Auditoria

A tabela `audit_log` registra **quem** (usuário), **o quê**, **quando** e **de onde** (IP e navegador): logins (sucesso,
falha, bloqueio), 2FA, reautenticação, trocas de senha, gestão de administradores, criação/alteração/exclusão de produtos
(com antes/depois), baixas de estoque, pedidos concluídos/cancelados, zerar histórico/vendas/relatório, reuso de refresh
token e acessos negados. **Nunca** grava senhas, tokens, códigos ou segredos. Retenção: 1 ano (`security.audit.retention`).

```bash
# últimos eventos de login bem-sucedido (mais recentes primeiro); filtros: action, entityType, page, size
curl -s "$API/api/admin/audit?action=LOGIN_SUCCESS&size=20" -H "Authorization: Bearer $TOKEN" | jq '.content[] | {occurredAt, actorEmail, ip, details}'
```

```sql
-- alterações de produto/estoque nos últimos 7 dias
SELECT occurred_at, actor_email, action, entity_id, details FROM audit_log
 WHERE action IN ('PRODUCT_CREATED','PRODUCT_UPDATED','PRODUCT_DELETED','STOCK_DECREASED')
   AND occurred_at > now() - interval '7 days' ORDER BY occurred_at DESC;

-- tentativas suspeitas
SELECT occurred_at, action, outcome, ip, details FROM audit_log
 WHERE action IN ('LOGIN_FAILURE','LOGIN_LOCKED','MFA_FAILURE','REFRESH_REUSE_DETECTED','ACCESS_DENIED','REAUTH_REQUIRED')
 ORDER BY occurred_at DESC LIMIT 100;
```

Sinais para investigar: `REFRESH_REUSE_DETECTED` (um refresh token roubado foi reutilizado — a sessão já foi derrubada),
`RECOVERY_CODE_USED` que não foi você, `ADMIN_CREATED`/`ADMIN_CREDENTIALS_RESET` inesperados, logins de IPs estranhos.

## 11. Backup e restauração

### O que existe

Um workflow do GitHub ([.github/workflows/db-backup.yml](../.github/workflows/db-backup.yml)) roda **todo dia** e:

1. faz `pg_dump` do banco de produção;
2. **restaura o dump** num banco descartável e confere que as tabelas e as migrations estão lá (um backup que não
   restaura não vale nada);
3. **cifra** o arquivo com a sua chave **pública** GnuPG e o guarda como artifact por 30 dias.

O dump contém hashes de senha, segredos de 2FA (cifrados) e dados de clientes (nome, WhatsApp, pedidos): por isso só o
arquivo cifrado sai do runner e a chave **privada** nunca fica no GitHub nem no Render.

> **Confira o plano do banco no Render.** Planos gratuitos costumam não ter backup gerenciado nem retenção longa (e o
> banco pode expirar). Este backup independe disso, mas só protege o que já foi copiado: não deixe a rotina sem
> monitoramento.

### Configuração (uma vez)

```bash
# 1) criar o par de chaves NO SEU COMPUTADOR (use uma senha forte e guarde a chave privada offline)
gpg --quick-gen-key "backup-fausto" rsa4096 encrypt 5y
FPR=$(gpg --list-keys --with-colons backup-fausto | awk -F: '/^fpr/ {print $10; exit}')

# 2) exportar a parte PÚBLICA (vai para o GitHub) e a PRIVADA (guarde em local seguro, fora do repositório)
gpg --armor --export "$FPR" > backup-publica.asc
gpg --armor --export-secret-keys "$FPR" > backup-PRIVADA.asc      # NUNCA suba este arquivo
```

No GitHub (Settings → Secrets and variables → Actions) crie:
`BACKUP_GPG_PUBLIC_KEY` (conteúdo de `backup-publica.asc`) e `BACKUP_DATABASE_URL` (URL **externa** do banco no Render,
com `?sslmode=require`). Rode o workflow uma vez em *Actions → Database backup → Run workflow* e confira que terminou
verde e que o artifact apareceu.

Avisos do GitHub: workflows agendados em repositório **público** são desativados após 60 dias sem atividade no repo, e
a notificação de falha vai por e-mail para quem criou/editou o agendamento (se as notificações dele estiverem ligadas).
Mesmo assim, **olhe a aba Actions de vez em quando**.

### Restaurar

```bash
# 1) baixe o artifact (aba Actions → run → fausto-db-backup) e descompacte
# 2) tenha a chave PRIVADA no gpg:  gpg --import backup-PRIVADA.asc
# 3) crie um banco NOVO e VAZIO e restaure nele
createdb -h HOST -U USUARIO fausto_restaurado
CONFIRM_RESTORE=yes scripts/db-restore.sh fausto-db-AAAAMMDDTHHMMSSZ.dump.gpg \
  "postgres://USUARIO:SENHA@HOST:5432/fausto_restaurado"
```

Para recuperar produção, aponte `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` da API para o banco restaurado e suba a API (o Flyway
confere as migrations). A restauração usa `--clean`: **apaga e recria** os objetos do banco de destino.

### Simulado de restauração (faça a cada 3 meses)

Backup só vale se restaura. Restaure o backup mais recente num banco novo, suba a API local apontando para ele e confira
que você entra, que `GET /api/product` lista os produtos e que `audit_log` tem registros recentes. Anote a data.
