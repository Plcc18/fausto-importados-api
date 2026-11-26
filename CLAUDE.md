# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Visão geral

API da loja Fausto Importados: Java 17, Spring Boot 3.5, Spring Security (JWT + 2FA), JPA, PostgreSQL, Flyway e Cloudinary. O front é outro repositório, em `c:\projects\typescript\fausto-importados-web`. Comentários, documentação e mensagens ao usuário são em português (várias mensagens de erro antigas estão em inglês).

## Comandos

No PowerShell use `.\mvnw.cmd` e coloque cada `-D...` entre aspas.

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # http://localhost:8080
./mvnw test
./mvnw test -Dtest=ProductServiceTest                   # uma classe
./mvnw test -Dtest='ProductServiceTest#nomeDoMetodo'    # um teste
./mvnw clean package -DskipTests                        # o mesmo build do Dockerfile (Render)
```

A configuração vem de variáveis de ambiente, ou de um arquivo `.env` na raiz (ver `.env.example`). `TOTP_ENCRYPTION_KEY` é obrigatória para a API subir.

Produção: API no Render e banco na Neon. O `DB_URL` usa a conexão direta (sem `-pooler`). Trate push na `main` como deploy em produção: o Flyway migra o banco quando a API sobe.

## Testes e banco

- A maioria dos testes roda em H2, com o schema gerado pelas entidades (Flyway desligado).
- Migrations, constraints e concorrência precisam de PostgreSQL real (`support/TestPostgres`): Docker ligado, ou um banco descartável com `-Dtest.postgres.url=...` (`-Dtest.postgres.user`, `-Dtest.postgres.password`). Sem nenhum dos dois, esses testes são **pulados** sem falhar a suíte; confira isso antes de considerar uma migration testada.
- Mudança de schema exige migration nova em `db/migration` (SQL do PostgreSQL) e entidade compatível: em produção o Hibernate usa `ddl-auto: validate`. Não edite migrations já aplicadas.

## Arquitetura

Camadas em `com.example.fausto_importados_api`: `controller` → `services` → `repository`, com `dto` e `mapper`. Os pontos abaixo atravessam várias classes.

### Segurança
- `SecurityConfig` nega por padrão: qualquer rota não listada exige `ADMIN`. Rota pública nova precisa de `permitAll` explícito; rotas administrativas também levam `@PreAuthorize`.
- Ordem dos filtros: JWT → `RateLimitFilter` → autorização. Em `/api/admin/**`, um usuário autenticado que não é admin recebe 404.
- `@RequiresReauth` exige o header `X-Reauth` (código 2FA recente), conferido pelo `ReauthInterceptor`.
- `/auth/*` chega pelo proxy da Vercel; o IP em `X-Client-IP` só é aceito com `PROXY_SHARED_SECRET` correto.
- O CORS usa `FRONTEND_URL`. Header de resposta novo que o front precise ler entra em `setExposedHeaders`.

### Escrita no banco
- Use `Transactions.run` / `execute`: READ COMMITTED com repetição em deadlock e lock timeout. O bloco pode executar mais de uma vez, não faz chamada externa lenta (Cloudinary) e deixa a auditoria para `afterCommit`. Respeite a ordem de locks documentada na classe.
- Edições usam lock otimista (`@Version` + `Versions.check`). POSTs sensíveis passam por `IdempotencyService`.
- `REQUIRE_VERSION` e `REQUIRE_IDEMPOTENCY_KEY` estão ligadas em produção (o padrão no código é `false`). Edição nova precisa conferir a `version` e POST sensível novo precisa aceitar `Idempotency-Key`, com o front enviando o dado.

### Auditoria, erros e tempo
- Toda operação administrativa registra um `AuditAction` pelo `AuditService`; antes e depois de produtos vêm de `AuditDiff`.
- Erros passam por `GlobalExceptionHandler` (e `AuthExceptionHandler`). Para um erro com status e `code` próprios, lance `ApiProblemException`.
- Injete o bean `Clock` em regras que dependem de tempo; os testes usam `MutableClock`.

### Domínio
- Produtos têm exclusão lógica (`active`); identidade única por nome, marca e tamanho entre os ativos. `stockQuantity` e `version` só são devolvidos a administradores.
- Pedidos são criados sem login, com nomes, preços e total calculados no servidor. O estoque só baixa quando o admin conclui o pedido.
- Upload (`/api/upload`) vai para o Cloudinary. Não há configuração de multipart no projeto, então vale o padrão do Spring (1 MB por arquivo).

## Documentação existente

- `docs/OPERATIONS.md`: variáveis, administradores, 2FA, auditoria, backup e cuidados de implantação.
- `README.md`: autenticação, rate limiting, administradores e concorrência estão detalhados ali. As seções de estrutura do banco, produtos, pedidos, pagamentos, Swagger, curl e estrutura de pastas estão desatualizadas; confira no código.

## Campanhas sazonais
- Tasks em `c:\projects\typescript\fausto-importados-web\docs\campanhas-sazonais-tasks.md` (as marcadas com `API` são deste repositório). Faça uma por vez e marque `[x]` só quando atender ao "Pronto quando".
- Ativar, encerrar ou editar uma campanha nunca altera `price` ou `originalPrice` dos produtos.
