# Integração Plugin 1.3.0 ⇄ BOT-ASTHEN 2.12.0

Este documento diz o que o plugin 1.3.0 faz e **o que o bot precisa mudar** (o plugin não altera o bot).

## O que já funciona com o bot 2.12.0, sem mudar nada

| Rota | Quem chama | Observação |
|---|---|---|
| `GET /plugin/v1/pull?limit=5&wait_ms=20000` | plugin | Long polling. O bot segura até 25 s e responde `200 {"requests":[...]}` ou `204`. |
| `POST /plugin/v1/result` | plugin | Resultado final. **Confirmado** quando o bot responde `200 {"success":true}`. |
| `POST /plugin/v1/kits` | plugin | Lista de kits com `price_cents` e `for_sale`. |

Todas usam `Authorization: Bearer <DKB_SECRET>`.

Como o plugin trata as respostas de `/result`:

| Resposta do bot | O que o plugin faz |
|---|---|
| `200` com `{"success":true}` | Confirmado: remove da outbox. |
| `200` sem `success:true` | **Não** confirma; tenta de novo. |
| `401`, `403` | Mantém, registra `AUTH_ERROR`, tenta de novo com espera. |
| `408`, `429`, `5xx`, timeout, rede | Mantém, tenta de novo com espera progressiva (2 s até 5 min). |
| `404` (o bot não conhece o `request_id`; hoje acontece se o bot reiniciou ou o pedido expirou) | Marca como **recusado**, guarda em `data/outbox.json` por 7 dias e registra `OUTBOX_REJECTED`. |
| `400`, `409`, `413`, `422` | Igual ao 404: recusado e guardado. |

## O que falta no bot: atualização posterior de status

Hoje o bot só recebe **um** resultado por pedido. Quando o inventário estava cheio, o plugin responde `KIT_PENDING`; o
bot fecha o pedido e **nunca fica sabendo** que o jogador recebeu depois. Por isso o Discord continua mostrando "pendente".

### Endpoint novo

```
POST /plugin/v1/events
Authorization: Bearer <DKB_SECRET>
Content-Type: application/json
```

Corpo:

```json
{
  "event_id": "discord-123456:KIT_DELIVERED",
  "request_id": "discord-123456",
  "status": "KIT_DELIVERED",
  "final": true,
  "success": true,
  "player": "Elber",
  "kit": "Kit_Ferro",
  "at": 1763251200000
}
```

- `event_id` = `request_id` + `:` + `status`. É **determinístico**: o mesmo fato sempre tem o mesmo id. O bot deve guardar os `event_id` já processados (por exemplo na tabela do pedido) e ignorar repetições.
- `request_id` é o do pedido original. O evento **nunca cria compra nem entrega nova**.
- Por enquanto o único `status` enviado é `KIT_DELIVERED` (entrega de uma pendência, ou confirmação de um admin com `/dk pending resolve ... delivered`). Entregas do `/dk give` não geram evento.

### Resposta esperada do bot

| Situação | Resposta |
|---|---|
| Evento aplicado **ou já aplicado antes** | `200 {"success":true}` |
| `request_id` desconhecido | `404 {"success":false,"status":"NOT_FOUND"}` |
| Corpo inválido | `400` |
| Token errado | `401` |

Atenção: `404` em **eventos** significa "este bot ainda não tem o endpoint" **ou** "pedido desconhecido". O plugin trata
o `404` de eventos como "ainda não suportado": **guarda o evento** e tenta de novo a cada 10 minutos (log
`EVENTS_UNSUPPORTED` uma vez). Para um pedido realmente desconhecido, o bot deve responder `409` ou `422` para o
plugin parar de insistir.

### Regras para o bot

1. Só aceitar a transição `KIT_PENDING` → `KIT_DELIVERED`. Um `KIT_DELIVERED` que chega sobre um pedido já `KIT_DELIVERED` é repetição: responder `200 {"success":true}` sem fazer nada.
2. Não reabrir pedidos falhos nem criar novos.
3. Responder `200 {"success":true}` **só depois** de gravar a mudança (o plugin apaga o evento da fila nesse momento).
4. Atualizar a mensagem do ticket/DM de "pendente" para "entregue" e, no Supabase, o estado do pedido.

### Mudanças sugeridas no repositório do bot

- `bot-integration/asthen-bridge.js`: nova rota `POST /plugin/v1/events` que valida o corpo e chama um callback `onEvent(event)` (como já existe `onKits`).
- `src/bridge.js`: repassar `onEvent`.
- `src/interactions.js`: tratar `KIT_DELIVERED` vindo de um evento atualizando o ticket (hoje `KIT_DELIVERED` e `KIT_PENDING` são tratados na linha de resultado final; reutilizar a mesma atualização de mensagem).
- `supabase/`: coluna ou tabela para os `event_id` processados.
- `test/bridge.test.js`: eventos repetidos, pedido desconhecido, token errado.

## Long polling

O plugin 1.3.0 envia `wait_ms = wait-seconds * 1000` (padrão `20000`, máximo `25000`). Se o bot ignorar o parâmetro e
responder `204` na hora, o plugin espera `interval-seconds` entre as consultas para não virar laço.
Detalhes: [LONG-POLLING.md](LONG-POLLING.md).
