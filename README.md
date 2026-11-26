# Fausto Importados API

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-blue?logo=openjdk&logoColor=white" alt="Java 17"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot"/>
  <img src="https://img.shields.io/badge/PostgreSQL-15-336791?logo=postgresql&logoColor=white" alt="PostgreSQL"/>
  <img src="https://img.shields.io/badge/Spring%20Security-JWT-orange?logo=jsonwebtokens&logoColor=white" alt="JWT"/>
  <img src="https://img.shields.io/badge/Flyway-DB--Migrations-red?logo=flyway&logoColor=white" alt="Flyway"/>
  <img src="https://img.shields.io/badge/Swagger-OpenAPI-85EA2D?logo=swagger&logoColor=black" alt="Swagger"/>
  <img src="https://img.shields.io/github/license/Plcc18/fausto-importados-api?color=yellow&logo=open-source-initiative&logoColor=white" alt="License"/>
  <img src="https://img.shields.io/github/languages/top/Plcc18/fausto-importados-api?color=blue&logo=java&logoColor=white" alt="Main Language"/>
  <img src="https://img.shields.io/github/last-commit/Plcc18/fausto-importados-api?logo=git&logoColor=white&color=brightgreen" alt="Last Commit"/>
  <img src="https://img.shields.io/github/repo-size/Plcc18/fausto-importados-api?color=blueviolet&logo=github" alt="Repo Size"/>
</p>

API RESTful para gerenciamento de uma loja de importados, com controle de **produtos**, **pedidos**, **pagamentos** e **autenticação administrativa**.

Desenvolvida com foco em simplicidade, escalabilidade e pronta para integração com frontend e serviços externos.

---

## Funcionalidades

| Módulo           | Funcionalidades                   |
| ---------------- | --------------------------------- |
| **Autenticação** | Login com JWT + 2FA (TOTP) para admins |
| **Produtos**     | CRUD completo + upload de imagens |
| **Pedidos**      | Criação e consulta de pedidos     |
| **Pagamentos**   | Registro e controle de transações |

> Apenas usuários **ADMIN** acessam a área administrativa (2FA obrigatório). Existe também a role `USER`
> (sem acesso administrativo); não há cadastro público. Clientes compram sem login (WhatsApp/frontend).

---

## Tecnologias Utilizadas

* **Java 17**
* **Spring Boot 3.5**
* **Spring Data JPA**
* **Spring Security + JWT**
* **PostgreSQL**
* **Flyway** (migrações de banco)
* **Cloudinary** (armazenamento de imagens)
* **Swagger / OpenAPI**
* **Maven**

---

## Estrutura do Banco de Dados

```sql
users (id, email, password, role)
products (id, name, description, price, image_url, stock)
orders (id, customer_name, total_price, status, created_at)
payments (id, order_id, amount, payment_method, status)
```

> As tabelas possuem restrições de integridade (`NOT NULL`, `FOREIGN KEY`, etc).

---

## Endpoints Principais

### Autenticação

Access token (JWT, 15 min) + refresh token opaco com **sessão deslizante** e rotação.

| Método | Endpoint                  | Descrição                                                                    | Acesso                     |
| ------ | ------------------------- | ---------------------------------------------------------------------------- | -------------------------- |
| POST   | `/auth/login`             | Confere a senha. Admin recebe um desafio de 2FA; os demais abrem a sessão    | Público (limitado)         |
| POST   | `/auth/login/mfa`         | Segundo fator: código do autenticador ou de recuperação → abre a sessão      | Desafio (limitado)         |
| POST   | `/auth/mfa/setup`         | Gera o segredo do 2FA (QR/chave) de uma conta que ainda não o tem            | Desafio                    |
| POST   | `/auth/mfa/setup/confirm` | Confirma o 1º código, ativa o 2FA, devolve os códigos de recuperação         | Desafio (limitado)         |
| POST   | `/auth/refresh`           | Troca o refresh token por um novo e devolve um novo access token             | Cookie + `Origin` + header |
| GET    | `/auth/me`                | Quem está logado: `{ email, role }`                                          | Bearer                     |
| POST   | `/auth/logout`            | Revoga a sessão atual e limpa o cookie                                       | Bearer (aceita já vencido) |
| POST   | `/auth/logout-all`        | Revoga todas as sessões do usuário                                           | Bearer                     |
| POST   | `/auth/reauth`            | Confirma a identidade com um código 2FA → prova de 5 min para o `X-Reauth`   | Bearer (ADMIN, limitado)   |
| POST   | `/auth/password`          | Troca a PRÓPRIA senha (senha atual + reautenticação); derruba as outras sessões | Bearer + `X-Reauth`     |
| POST   | `/auth/mfa/recovery-codes`| Gera 10 códigos de recuperação novos (os antigos deixam de valer)            | Bearer + `X-Reauth`        |

```json
// POST /auth/login
{ "email": "admin@fausto.com", "password": "123456" }
// usuário comum → 200 { "status": "AUTHENTICATED", "accessToken": "<jwt>", "expiresIn": 900 } + Set-Cookie: refresh_token=...
// admin com 2FA  → 200 { "status": "MFA_REQUIRED", "challenge": "<jwt de 5 min>" }            (sem sessão ainda)
// admin sem 2FA  → 200 { "status": "MFA_SETUP_REQUIRED", "challenge": "...", "passwordChangeRequired": false }
// muitas falhas  → 429 + Retry-After
```

**Login do administrador (2FA obrigatório):** senha → `challenge` → `POST /auth/login/mfa { challenge, code }`.
No primeiro acesso (conta sem 2FA): `POST /auth/mfa/setup { challenge }` devolve `secret` (Base32) e `otpauthUri`
(o front desenha o QR); `POST /auth/mfa/setup/confirm { challenge, code, newPassword? }` ativa o 2FA, devolve 10
`recoveryCodes` (aparecem **uma única vez**) e abre a sessão. O `challenge` não vale como access token.

Como funciona:

- **Access token**: JWT assinado com `JWT_SECRET`, vida de 15 min, payload `sub` (id), `role` e `sid` (id da sessão). Enviado em `Authorization: Bearer`; o front o guarda só em memória.
- **Refresh token**: 256 bits aleatórios, no cookie `refresh_token` (`HttpOnly`, `Secure`, `SameSite=Strict`, `Path=/auth/refresh`). No banco fica **só o hash SHA-256** (tabela `refresh_tokens`).
- **Sessão deslizante**: cada refresh zera o prazo de inatividade (`REFRESH_INACTIVITY`, 7 dias) mas nunca passa do limite absoluto (`REFRESH_ABSOLUTE`, 30 dias).
- **Rotação e reuso**: cada refresh gera um token novo e invalida o anterior, com `SELECT ... FOR UPDATE`. Reapresentar um token já trocado revoga a família inteira, exceto dentro de `REFRESH_GRACE` (10 s), janela para requisições concorrentes legítimas (várias abas): nela só se devolve um novo access token, sem rotacionar de novo.
- **CSRF no refresh**: `SameSite=Strict` + `Origin` na lista de `FRONTEND_URL` + header `X-Requested-With: fausto-web`.
- **Revogação**: logout, "sair de todos" e troca de senha (`PUT /api/user/{id}` com senha) revogam sessões; vale imediatamente porque o filtro confere o `sid` a cada request.
- **Limpeza**: `RefreshTokenCleanupJob` apaga tokens vencidos diariamente (cron em `security.auth.cleanup-cron`).
- Erros de login/refresh são genéricos e não revelam a causa.
- **2FA (TOTP, RFC 6238)**: códigos de 6 dígitos / 30 s via biblioteca `java-otp`; aceita ±1 passo de relógio e
  cada código vale **uma vez** (o último passo aceito fica no banco, atualizado de forma atômica). O segredo é
  cifrado em repouso com **AES-256-GCM** (`TOTP_ENCRYPTION_KEY`; o id do usuário entra como AAD). Códigos de
  recuperação: 10, 80 bits, só o hash SHA-256 é guardado, uso único.
- **Força bruta**: bloqueio temporário progressivo **por conta** (5 falhas → 1 min, dobrando até 30 min) e por IP
  (20 falhas → 5 min...). Senha errada e código 2FA errado somam no mesmo contador, e e-mails inexistentes contam
  igual (o bloqueio não revela se a conta existe). Os contadores ficam no PostgreSQL (`auth_throttle`).
  O IP do cliente vem de `X-Forwarded-For` contando `TRUSTED_PROXY_HOPS` itens a partir do fim (ver **Rate limiting**).
- **Primeiro admin**: criado no startup só se não existir nenhum, a partir de `ADMIN_EMAIL`/`ADMIN_PASSWORD`;
  o 2FA é cadastrado no primeiro login e as duas variáveis podem sair do ambiente depois.
- **401 × 403**: sem token (ou token inválido) → 401; autenticado sem permissão (ex.: `USER` numa rota admin) → 403.

> `JWT_EXPIRATION` (24h) não é mais usado. Use `JWT_ACCESS_TTL` (ISO-8601, padrão `PT15M`). Remova a variável antiga do ambiente de produção.

---

### Rate limiting

Algoritmo **token bucket**: o balde começa cheio (o máximo de uma vez) e recupera `máximo/janela` fichas por unidade de
tempo; uma janela inteira sem uso o enche de novo. A checagem e o gasto da ficha são atômicos (script Lua no Redis;
`SELECT ... FOR UPDATE` no Postgres), então requisições simultâneas não furam o limite.

| Regra (variável) | Padrão | Chave |
|---|---|---|
| `RATE_LIMIT_LOGIN_IP` | `5/15m` | IP, em `/auth/login` e `/auth/login/mfa` |
| `RATE_LIMIT_LOGIN_EMAIL` | `5/15m` | hash do e-mail (conta comum) |
| `RATE_LIMIT_LOGIN_ADMIN_EMAIL` | `3/15m` | hash do e-mail (admin **e e-mail inexistente**, para o 429 não revelar quem é admin) |
| `RATE_LIMIT_REFRESH` | `30/1m` | hash do cookie de refresh (sessão) |
| `RATE_LIMIT_PUBLIC_WRITE` | `3/1h` | IP, em `POST /api/orders` e nos caminhos de `security.rate-limit.sensitive-paths` (cadastro e recuperação de senha, quando existirem) |
| `RATE_LIMIT_API_USER` | `100/1m` | usuário logado (independe do IP) |
| `RATE_LIMIT_API_ANON` | `60/1m` | IP, sem login |

Formato `máximo/janela` (`5/15m`, `100/1m`, `3/1h`). `RATE_LIMIT_ENABLED=false` desliga tudo. Além disto, o bloqueio
progressivo do login (`LoginThrottle`, dobra até 30 min e libera sozinho) continua valendo por conta e por IP.

- **Resposta**: 429 com `Retry-After`, `RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset` (segundos) e a
  mensagem genérica `Too many requests. Try again later.`. As demais respostas também levam os `RateLimit-*`. Os
  cabeçalhos são expostos no CORS para o front poder lê-los.
- **Onde ficam os baldes** (`RATE_LIMIT_STORE`, padrão `auto`): `REDIS_URL` definido → Redis; senão, memória nos perfis
  `dev`/`test` (cada instância conta sozinha: só para desenvolvimento) e **Postgres** nos demais (tabela
  `rate_limit_bucket`, compartilhada entre instâncias, algumas consultas por requisição). Para trocar do Postgres para o
  Redis basta definir `REDIS_URL` (`redis://` ou `rediss://`); nada mais muda. Se o armazenamento falhar, as requisições
  passam (e o erro vai para o log): o login segue protegido pelo `LoginThrottle`.
- **IP real**, por dois caminhos:
  - **Chamadas diretas do navegador ao Render** (`/api/**`, só o Render na frente): o Render acrescenta ao **fim** de
    `X-Forwarded-For` quem conectou; o que vem antes é escrito pelo cliente e pode ser forjado. Com `TRUSTED_PROXY_HOPS`
    = N proxies na frente (**1** em `prod`; `0` em `dev`, que ignora o cabeçalho), o IP é o item N contando do fim.
    Itens inválidos ou em menor número que N caem no endereço da conexão. Em IPv6 o limite é por faixa /64.
    Com um valor maior que o real (era 2 antes), todo mundo cai no endereço da conexão e divide o mesmo balde.
  - **`/auth/*` pela Vercel**: o rewrite da Vercel **não** entrega o IP do visitante (a API só via o endereço de saída
    da Vercel, o mesmo para todos). Por isso `/auth/*` passa por uma função de borda no front
    (`api/auth/[...path].ts`), que lê o IP preenchido pela Vercel e o repassa em `X-Client-IP`, junto com
    `X-Proxy-Secret`. A API só acredita no IP quando o segredo confere (`PROXY_SHARED_SECRET`, mínimo 32 caracteres,
    comparação em tempo constante); sem o segredo o cabeçalho é ignorado.
  - **`PROXY_REQUIRE_FOR_AUTH=true`** faz `/auth/**` responder **404** a quem não trouxer o segredo: ninguém chega ao
    login pelo Render direto, então o limite por IP não se contorna. Exige `PROXY_SHARED_SECRET`. Ligue só depois que o
    front estiver enviando o segredo (a API recusa subir se faltar). Os comandos do runbook que chamam `/auth/login` na
    API direto passam a precisar de `-H "X-Proxy-Secret: $PROXY_SHARED_SECRET"`.
  O limite por e-mail e por sessão não depende de IP e continua valendo em qualquer caso.
- **Área admin oculta**: para um usuário autenticado que não é admin, `/api/admin/**` responde **404** e nunca
  passa pelo limitador (não existe 429 que revele a rota). Sem autenticação, o 401 comum de qualquer rota protegida.
- **Monitoramento**: ao atingir o limite, um `WARN` `Rate limit atingido: rule=... subject=... ip=...` (no máximo um por
  balde por minuto) e um evento `RATE_LIMITED` na auditoria. Nunca registra e-mail ou token em texto, só hash.

### Administradores e auditoria

Cada administrador tem a própria conta (2FA individual). Não há cadastro público: só um admin autenticado cria outro.
Todas as alterações abaixo exigem **reautenticação recente** (`POST /auth/reauth` → header `X-Reauth`); sem ela a
resposta é `403 { "code": "reauth_required" }`.

| Método | Endpoint                          | Descrição                                                              | Acesso            |
| ------ | --------------------------------- | ---------------------------------------------------------------------- | ----------------- |
| GET    | `/api/user`, `/api/user/{id}`     | Lista/consulta contas (`active`, `mfaEnabled`)                         | ADMIN             |
| POST   | `/api/user`                       | Cria admin a partir do e-mail; devolve **senha temporária (uma vez)**  | ADMIN + reauth    |
| PUT    | `/api/user/{id}`                  | Troca o e-mail (a senha NÃO muda por aqui)                             | ADMIN + reauth    |
| PATCH  | `/api/user/{id}/active`           | `{ "active": false }` desativa e encerra as sessões na hora            | ADMIN + reauth    |
| POST   | `/api/user/{id}/reset-credentials`| Senha esquecida / aparelho perdido: nova senha temporária e 2FA a refazer | ADMIN + reauth |
| DELETE | `/api/user/{id}`                  | Exclui a conta (prefira desativar)                                     | ADMIN + reauth    |
| GET    | `/api/admin/audit`                | Trilha de auditoria (`page`, `size`, `action`, `entityType`)           | ADMIN             |

Regras: ninguém desativa/exclui/redefine a **própria** conta por essas rotas e o **último admin ativo** nunca pode ser
removido ou desativado (409). A senha de OUTRA pessoa nunca é definida por um admin: o servidor gera uma senha
temporária de 20 caracteres, mostrada uma única vez, que o novo admin troca ao cadastrar o 2FA em até 24 h
(depois disso a conta só volta com um novo `reset-credentials`).

**Auditoria** (`audit_log`): quem (usuário), o quê (ação/entidade, com antes/depois em alterações de produto), quando,
IP e user-agent. Registra login (sucesso, falha, bloqueio), 2FA, reautenticação, troca de senha, gestão de admins,
criação/alteração/exclusão de produtos, baixa de estoque, pedidos concluídos/cancelados, zerar histórico/vendas/relatório,
reuso de refresh token e acessos negados (403, com limite por minuto). **Nunca** grava senhas, tokens, códigos ou
segredos (chaves sensíveis em `details` viram `[redacted]`). Retenção: 1 ano (`security.audit.retention`, ISO-8601).

---

### Concorrência e consistência

- **Transações**: READ COMMITTED com locks explícitos (`SELECT ... FOR UPDATE`), updates atômicos e constraints no
  banco. Deadlock, erro de serialização e lock timeout (5 s) repetem a transação até 3 vezes, com backoff. Ordem de
  locks: pedido → produtos por id; administradores → usuário → tokens e códigos.
- **Estoque**: baixa atômica (`UPDATE ... WHERE stock_quantity >= ?`), tudo ou nada.
- **Pedidos**: preços, nomes e total vêm do cadastro dos produtos; o que o cliente envia nesses campos é ignorado.
- **Lock otimista**: `GET /api/product*` (autenticado como ADMIN) e `GET /api/user*` devolvem `version`. Envie-a no
  PUT/PATCH; se o registro mudou (outra edição ou uma venda), a resposta é `409 { "code": "version_conflict" }`.
  Opcional até `REQUIRE_VERSION=true`.
- **Catálogo**: `GET /api/product*` é público, mas com um token inválido, vencido ou revogado responde 401 (para o
  front renovar a sessão em vez de receber a resposta sem `stockQuantity`).
- **Idempotency-Key** (16 a 100 caracteres; use um UUID novo por ação do usuário e repita-o nas novas tentativas):
  `POST /api/orders`, `/api/orders/{id}/complete`, `/api/orders/{id}/cancel`, `/api/product`,
  `/api/product/decrease-stock`, `/api/user`, `/api/user/{id}/reset-credentials`. A repetição devolve a resposta
  original com `Idempotent-Replayed: true` (sem a senha temporária, que só aparece uma vez); a mesma chave com outro
  corpo dá 422. Falhas não ficam guardadas. Vale por 1 dia. Opcional até `REQUIRE_IDEMPOTENCY_KEY=true`.
- **Testes**: precisam de PostgreSQL. Com Docker ligado basta `./mvnw test`; sem Docker, aponte um banco descartável:
  `./mvnw test -Dtest.postgres.url=jdbc:postgresql://localhost:5432/banco_de_teste -Dtest.postgres.user=postgres`.
  Sem nenhum dos dois, os testes de concorrência são **pulados**.

Antes de implantar, veja [docs/OPERATIONS.md](docs/OPERATIONS.md), seção 12.

---

### Produtos

| Método | Endpoint             | Descrição           | Acesso  |
| ------ | -------------------- | ------------------- | ------- |
| GET    | `/api/products`      | Lista produtos      | Público |
| GET    | `/api/products/{id}` | Busca produto       | Público |
| POST   | `/api/products`      | Cria produto        | ADMIN   |
| PUT    | `/api/products/{id}` | Atualiza produto    | ADMIN   |
| PATCH  | `/api/products/{id}` | Atualização parcial | ADMIN   |
| DELETE | `/api/products/{id}` | Remove produto      | ADMIN   |

---

### Pedidos

| Método | Endpoint           | Descrição       | Acesso  |
| ------ | ------------------ | --------------- | ------- |
| POST   | `/api/orders`      | Cria pedido     | Público |
| GET    | `/api/orders/{id}` | Consulta pedido | ADMIN   |

---

### Pagamentos

| Método | Endpoint        | Descrição          | Acesso |
| ------ | --------------- | ------------------ | ------ |
| POST   | `/api/payments` | Registra pagamento | ADMIN  |

---

## Upload de Imagens

As imagens dos produtos são armazenadas utilizando o **Cloudinary**.

Fluxo:

1. Upload da imagem via API
2. Cloudinary retorna uma URL
3. URL é salva no campo `image_url` do produto

---

## Operação (administradores, 2FA, auditoria e backup)

O passo a passo para criar e desativar administradores, recuperar o 2FA, consultar a auditoria e fazer/restaurar
backup está em **[docs/OPERATIONS.md](docs/OPERATIONS.md)**. Há um backup diário cifrado
([.github/workflows/db-backup.yml](.github/workflows/db-backup.yml)) com restauração testada
([scripts/db-restore.sh](scripts/db-restore.sh)).

---

## Como Executar

### Pré-requisitos

* Java 17+
* Maven
* PostgreSQL

---

### Passos

1. **Clone o repositório**

```bash
git clone https://github.com/Plcc18/fausto-importados-api.git
cd fausto-importados-api
```

2. **Configure o banco de dados**

```bash
createdb fausto_importados_db
```

3. **Configure as variáveis de ambiente**

A configuração fica em `src/main/resources/application.yml` (YAML), que só lê variáveis
de ambiente — nenhum segredo é commitado. Copie `application.yml.example` como referência
e exporte no seu ambiente/IDE:

```bash
export DB_URL=jdbc:postgresql://localhost:5432/fausto_importados_db
export DB_USERNAME=seu_usuario
export DB_PASSWORD=sua_senha

export JWT_SECRET=$(openssl rand -base64 32)

export CLOUDINARY_NAME=seu_cloud_name
export CLOUDINARY_KEY=sua_api_key
export CLOUDINARY_SECRET=sua_api_secret

# Chave AES-256 (base64, 32 bytes) que cifra os segredos do 2FA. OBRIGATÓRIA — a API não sobe sem ela.
# Guarde uma cópia fora do Render: perdê-la obriga todos os admins a refazer o 2FA.
export TOTP_ENCRYPTION_KEY=$(openssl rand -base64 32)

# Só para criar o PRIMEIRO admin (quando ainda não existe nenhum); depois do 1º login podem sair do ambiente
export ADMIN_EMAIL=admin@fausto.com
export ADMIN_PASSWORD=sua_senha_de_admin
```

4. **Execute a aplicação**

```bash
mvn spring-boot:run
```

---

## Acesso

* API:

```
http://localhost:8080
```

* Swagger:

```
http://localhost:8080/swagger-ui.html
```

---

## Testes Rápidos (curl)

```bash
# Login
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@fausto.com","password":"123456"}'

# Criar produto
curl -X POST http://localhost:8080/api/products \
  -H "Authorization: Bearer SEU_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Perfume X","price":199.90,"stock":10}'
```

---

## Estrutura do Projeto

```
src/
├── main/
│   ├── java/com/fausto/importados/
│   │   ├── controller/     → Endpoints REST
│   │   ├── dto/            → DTOs
│   │   ├── entity/         → Entidades JPA
│   │   ├── repository/     → Repositórios
│   │   ├── service/        → Regras de negócio
│   │   ├── security/       → JWT e configurações
│   │   └── FaustoImportadosApplication.java
│   └── resources/
│       ├── db/migration/   → Scripts Flyway
│       └── application.yml
```

---

## Migração com Flyway

```sql
-- V1__create_tables.sql
CREATE TABLE products (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10,2) NOT NULL,
    stock INT NOT NULL
);
```

> Execute:

```bash
mvn flyway:migrate
```

---

## Segurança

* Autenticação via **JWT** (access token curto) + refresh token rotativo
* **2FA (TOTP)** obrigatório para ADMIN, com códigos de recuperação
* Bloqueio temporário progressivo contra força bruta
* Senhas com **BCrypt**
* Acesso restrito para **ADMIN** (401 sem login, 403 sem permissão)
* Endpoints públicos:

    * `/api/auth/**`
    * `/api/products (GET)`

---

## Contribuição

1. Fork do projeto
2. Crie uma branch: `git checkout -b feature/nova-feature`
3. Commit: `git commit -m "feat: nova feature"`
4. Push: `git push origin feature/nova-feature`
5. Abra um Pull Request

---

## Autor

**Plcc18**
[https://github.com/Plcc18](https://github.com/Plcc18)

---

## Licença

Este projeto está sob a licença MIT.
Veja o arquivo `LICENSE` para mais detalhes.

---

**Fausto Importados API** — Backend robusto para e-commerce moderno 🚀
