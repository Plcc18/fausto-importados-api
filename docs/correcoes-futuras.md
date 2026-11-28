# Correções futuras (API)

Bugs conhecidos que **podem esperar** e serão corrigidos depois que tudo estiver em produção (T40 e T41 das campanhas sazonais, em `docs/campanhas-sazonais-tasks.md` do repositório `fausto-importados-web`). O front-end tem a própria lista, em `docs/correcoes-futuras.md` daquele repositório.

## Regra

Vale para qualquer pessoa ou agente que mexer neste repositório.

Achou um bug (revisão, teste ou uso) que pode esperar a produção? **Registre aqui**, em vez de corrigir na hora ou misturar à task em andamento.

**Não pode esperar** (corrija antes de seguir):

- falha de segurança ou exposição de dados;
- perda ou corrupção de dados, inclusive migration que possa falhar em produção;
- preço, estoque ou total de pedido errados;
- quebra de algo que a API já faz (catálogo, pedido, login, administração) ou do contrato usado pelo front;
- bug que impede o "Pronto quando" da task em andamento, ou faz teste ou build falharem.

Na dúvida, pergunte a quem cuida do projeto.

## Como registrar

- Coloque o item no repositório onde está o código com problema.
- Use o próximo número (`A01`, `A02`...). Número usado não muda nem é reaproveitado.
- Cite classe e método, não o número da linha (ele muda).
- Ao corrigir, marque `[x]` e acrescente a data e o PR. Não apague o item.

Modelo:

```markdown
- [ ] **A00. Título curto** · Importante ou Menor
  - *Origem:* revisão, teste ou uso (data, PR).
  - *Onde:* classe e método.
  - *Problema:* o que acontece e para quem.
  - *Como reproduzir:* passos ou cenário.
  - *Sugestão:* caminho para corrigir.
```

## Pendências

Nenhuma registrada.
