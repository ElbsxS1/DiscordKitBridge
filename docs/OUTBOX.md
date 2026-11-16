# Outbox

`data/outbox.json` guarda tudo que o bot ainda precisa saber:

- `result`: resultado final de um pedido (`POST /plugin/v1/result`);
- `event`: atualização posterior, como `KIT_PENDING` → `KIT_DELIVERED` (`POST /plugin/v1/events`).

Regras:

1. **grava antes de enviar**; se não conseguir gravar, mantém em memória e tenta gravar de novo;
2. só remove quando o bot confirma com `200 {"success":true}`;
3. sobrevive a reinício; ao iniciar, tudo volta a ser elegível para envio;
4. id fixo por item (`result:<request_id>`, `event:<request_id>:<status>`): adicionar de novo não duplica;
5. falhas temporárias (rede, timeout, 401, 403, 408, 429, 5xx): espera progressiva de 2 s até 5 min, **nunca descarta**;
6. recusas definitivas (400, 404 em resultado, 409, 413, 422): ficam como "recusado" no arquivo por 7 dias (para você ver), com log `OUTBOX_REJECTED`; não são reenviadas;
7. `404` em **evento** = endpoint ainda inexistente no bot: guarda e tenta a cada 10 min;
8. arquivo ilegível é movido para `outbox.json.corrupt-<hora>`.

`/dk pending` mostra quantos itens aguardam o bot e quantos foram recusados.

Limite: 2000 itens; acima disso só os "recusados" antigos são removidos, nunca os pendentes.
