# DiscordKitBridge (Plugin-ASTHEN)

Plugin **Paper** (Minecraft 26.3, Java 25) que entrega kits pedidos pelo bot do Discord.

- O administrador cadastra kits **segurando uma Shulker Box** e usando `/dk create <nome>`.
- Quando uma compra é aprovada no Discord, o bot manda **nome + plataforma + kit** e o plugin entrega os itens.
- O plugin tem **seu próprio** cadastro, armazenamento e entrega. Não usa comandos nem dados de outros plugins de kit.
- Funciona com jogadores **Java** e **Bedrock** (via Geyser + Floodgate).

> **Por que Paper e não PocketMine?** O servidor onde isto roda é Paper (Java). Um plugin PocketMine (`.phar`, PHP) não carrega num servidor Paper.

---

## Requisitos

| Item | Versão |
|---|---|
| Servidor | Paper 26.3 (ou compatível com `api-version 26.3`) |
| Java do servidor | 25 |
| Bedrock (opcional) | Geyser + Floodgate instalados no servidor |
| Bot | Node.js 18+ (`bot-integration/asthen-bridge.js`, sem dependências) |

Para **compilar**: JDK 25 e Maven (ou use o GitHub Actions deste repositório, que gera o `.jar`).

## Instalação

1. Baixe `DiscordKitBridge-1.4.0.jar` (aba *Actions* → último build → *Artifacts*, ou compile com `mvn package`).
2. Coloque na pasta `plugins/` do servidor e reinicie.
3. Abra `plugins/DiscordKitBridge/config.yml`:
   - O plugin gera um **token forte** em `bridge.secret` na primeira execução. Copie-o para o bot.
   - Preencha `bridge.poll.url` com o endereço público do seu bot (veja *Integração com o bot*).
4. `/dk reload`.

## Cadastrar um kit (Shulker Box)

1. Monte os itens dentro de uma Shulker Box (encantamentos, nomes, lore etc. já nos itens).
2. **Segure a Shulker Box na mão principal.**
3. `/dk create VipGuloso`

```
[DiscordKitBridge] Kit VipGuloso cadastrado com sucesso!
```

Cada item é salvo com `ItemStack#serializeAsBytes()`, o formato nativo e versionado do próprio servidor. Por isso encantamentos, nome personalizado, lore, durabilidade e todos os componentes são preservados exatamente, inclusive depois de atualizar o servidor.
O kit fica em `plugins/DiscordKitBridge/kits/VipGuloso.json` e continua disponível depois de reiniciar.

Regras de nome: 1 a 32 caracteres, só letras, números, `_` e `-`. Nomes iguais (mesmo mudando maiúsculas) são recusados. Uma Shulker Box vazia é recusada.

## Preço do kit

O preço fica no **plugin** (uma única fonte da verdade) e o bot só lê:

```
/dk create Kit_Dima_Evo        cria o kit (sem preço)
/dk price Kit_Dima_Evo 30      define R$ 30,00
/dk price Kit_Dima_Evo 45,90   muda para R$ 45,90, sem apagar nem recriar nada
/dk disable Kit_Dima_Evo       desativa: fica "indisponível" na loja
```

Regras: valor maior que zero, até `999999,99`, com vírgula ou ponto. Kit **sem preço não está à venda**.
A lista de kits que o bot recebe traz, para cada kit: `name`, `enabled`, `items`, `price` (reais), `price_cents`
(centavos, valor exato) e `for_sale` (`true` só se estiver ativo **e** com preço). Desativado ou sem preço = indisponível.

## Comandos e permissões

| Comando | Permissão | O que faz |
|---|---|---|
| `/dk create <nome>` | `discordkitbridge.create` | Salva a Shulker Box da mão como kit |
| `/dk delete <nome>` | `discordkitbridge.delete` | Remove o kit. **Pede confirmação**: repita com `confirm` em até 30 s |
| `/dk list` | `discordkitbridge.list` | Lista os kits |
| `/dk info <nome>` | `discordkitbridge.list` | Nome, nº de itens, data de cadastro, status |
| `/dk preview <nome>` | `discordkitbridge.preview` | Abre uma janela só de leitura com os itens |
| `/dk give <nome> [jogador]` | `discordkitbridge.give` | Entrega o kit a você (ou ao jogador indicado). Inventário cheio → fica pendente |
| `/dk price <nome> <valor>` | `discordkitbridge.price` | Define o preço em reais (`30`, `29,90` ou `29.90`). `remover` tira o kit da venda |
| `/dk enable <nome>` / `/dk disable <nome>` | `discordkitbridge.toggle` | Ativa / desativa um kit. Desativado não é entregue e aparece como indisponível na loja |
| `/dk toggle <nome>` | `discordkitbridge.toggle` | Alterna entre ativo e desativado |
| `/dk claim` | `discordkitbridge.claim` (todos) | Recebe kits pendentes (inventário cheio) |
| `/dk pending` | `discordkitbridge.pending` | Lista entregas que precisam de atenção. `/dk pending resolve <request_id> <delivered\|retry>` decide uma entrega interrompida |
| `/dk reload` | `discordkitbridge.reload` | Recarrega config e kits |

`discordkitbridge.admin` (padrão: operadores) inclui create, delete, list, preview, give, price, toggle, reload e pending.
Jogadores comuns só têm `claim`. **Cada comando confere a própria permissão**, inclusive as sugestões do Tab: quem só
tem `discordkitbridge.price` não vê nem completa `/dk delete`. `/dk` sozinho mostra só os comandos que você pode usar.
Detalhes e migração: [docs/MIGRATION-1.3.0.md](docs/MIGRATION-1.3.0.md).

## Como a entrega funciona

1. Pedido recebido e validado (todos os campos têm lista de caracteres permitidos).
2. **Plataforma**: o plugin pergunta ao **Floodgate** se o jogador é Bedrock. O nome sozinho nunca prova a plataforma.
   - `bedrock`: o jogador tem de ser Floodgate. Nomes Bedrock aparecem com prefixo (`.Elber`); o plugin aceita o nome com ou sem ele.
   - `java`: o jogador tem de **não** ser Floodgate.
   - Sem Floodgate instalado, pedidos `bedrock` são recusados (`PLATFORM_UNVERIFIABLE`), a menos que você ligue `platform.allow-unverified-bedrock` (inseguro: um jogador Java poderia usar um nome com `.`).
3. O jogador precisa estar online (`kits.require-online`).
4. O kit é carregado e **todos os itens são lidos antes de entregar qualquer um**.
5. **O jogador recebe uma Shulker Box com os itens dentro**, na mesma cor da Shulker Box usada no `/dk create`
   (kits cadastrados em versões antigas, sem cor guardada, saem na roxa padrão). A caixa leva o nome do kit e cada
   item fica no mesmo slot do original. Como é **um único item**, o kit precisa de **um slot livre** no inventário.
6. **Entrega tudo ou nada**: o plugin testa se a caixa cabe. Se couber, entrega. Se não couber:
   - `pending-if-inventory-full: true` → guarda o kit **inteiro** como pendente, avisa o jogador, e ele recebe **sozinho assim que liberar espaço** (auto-claim, 1.3.0), com `/dk claim` ou ao entrar. Nunca entrega só uma parte.
   - `false` → recusa (`INVENTORY_FULL`).
7. O kit pendente é uma **cópia**: apagar ou editar o kit depois não tira o que o jogador já comprou.

Todo acesso a inventário acontece na **thread principal** do servidor. A comunicação com o Discord roda fora dela, então o servidor não trava.

### Pendências, auto-claim e recuperação (1.3.0)

- Cada pendência tem um **estado salvo em disco**: `PENDING`, `CLAIMING`, `DELIVERED`, `RETRY`, `ERROR`.
- Arquivo ilegível **nunca vira "lista vazia"**: é restaurado do backup ou guardado como `.corrupt-<hora>` e o problema aparece no console e em `/dk pending`.
- Se o servidor cair no meio de uma entrega (`CLAIMING`), o plugin **não entrega de novo sozinho**; o admin confere o inventário e decide com `/dk pending resolve`.
- Jogador offline (`kits.require-online: false`): a pendência é ligada ao UUID quando ele entra (por plataforma, Java e Bedrock não se misturam).
- **Auto-claim**: ao fechar um inventário, soltar um item ou clicar no inventário, se houver kit pendente o plugin tenta de novo no tick seguinte (no máximo uma tentativa por vez, com intervalo, e o aviso "inventário cheio" no máximo a cada 30 s).
- Quando um kit pendente é entregue, o plugin avisa o bot (`KIT_PENDING` → `KIT_DELIVERED`) pela **outbox** persistente. O bot precisa do endpoint descrito em [docs/BOT-INTEGRATION-1.3.0.md](docs/BOT-INTEGRATION-1.3.0.md).
- Mais: [docs/PENDING-AND-RECOVERY.md](docs/PENDING-AND-RECOVERY.md), [docs/LONG-POLLING.md](docs/LONG-POLLING.md), [docs/OUTBOX.md](docs/OUTBOX.md).

### Sem entrega duplicada

Cada pedido tem um `request_id` único, guardado em disco (`data/requests.json`, 7 dias). Repetir o mesmo `request_id` **nunca entrega de novo**: devolve o resultado original, mesmo depois de reiniciar. Também é recusado (`DUPLICATE_REQUEST`) um segundo pedido idêntico (mesmo jogador + kit) enquanto o primeiro ainda está em andamento.

## Integração com o bot

O plano gratuito do Shulkerboxes **não abre porta de entrada**. Por isso o modo padrão é **`poll`**: o plugin só faz conexões **de saída**, consultando o bot.

```
Compra aprovada → bot.deliverKit() → [fila no bot]
                                        ↑ GET  /plugin/v1/pull       (plugin consulta a cada 3 s)
                                        ↓ POST /plugin/v1/result     (plugin devolve o resultado)
```

### 1. No bot (Node.js)

Copie `bot-integration/asthen-bridge.js` para o projeto do bot e suba o servidor:

```js
const { startBridge } = require('./asthen-bridge');

const bridge = startBridge({
  token: process.env.DKB_SECRET,   // o MESMO token de bridge.secret do plugin
  port: 8787,
  onKits: (kits) => console.log('Kits no servidor:', kits.map(k => k.name)),
});
```

Quando a equipe **aprovar** a compra (no seu fluxo de loja):

```js
const result = await bridge.deliverKit({
  requestId: `compra-${ticketId}`,   // único por compra; reutilize o MESMO id ao tentar de novo
  player: nickDoMinecraft,
  platform: 'bedrock',               // ou 'java' — pergunte ao cliente junto com o nick
  kit: nomeDoKit,                    // igual ao /dk create
  timeoutMs: 60000,
});

if (result.success) {
  // result.status: 'KIT_DELIVERED' ou 'KIT_PENDING' (inventário cheio)
} else {
  // result.status: PLAYER_OFFLINE, KIT_NOT_FOUND, PLATFORM_MISMATCH, ...  result.message explica
}
```

`result.history` traz cada etapa com horário (`LOADING_PLAYER` → `PLAYER_ON` → `LOADING_KIT` → `DELIVERING_KIT` → `KIT_DELIVERED`) para você montar as mensagens de progresso no Discord.

O nome do kit na sua loja e o do `/dk create` precisam ser **idênticos**. O plugin envia a lista de kits ao bot (`onKits`), então dá para conferir.

### 2. Tornar o bot alcançável

O plugin precisa abrir `bridge.poll.url`. O bot precisa estar num lugar com endereço público:

- **VPS** com IP/domínio: use um proxy reverso com **HTTPS** (Caddy/Nginx).
- **Seu PC**: use um túnel (por exemplo Cloudflare Tunnel) que dá uma URL HTTPS pública para `localhost:8787`.

Sem HTTPS o token trafega em texto puro; o plugin avisa no console se a URL for `http://` pública.

### 3. No plugin (`config.yml`)

```yaml
bridge:
  mode: "poll"
  secret: "O_MESMO_VALOR_DO_DKB_SECRET_DO_BOT"   # só o valor, sem "DKB_SECRET="
  poll:
    url: "https://seu-bot.exemplo.com"           # sem barra no final e sem /plugin/v1
    long-poll:
      enabled: true      # exige BOT-ASTHEN 2.12.0+; false = consulta a cada interval-seconds
      wait-seconds: 20
```

## API REST (modo `server`)

Use só se o seu servidor puder **receber** conexões (VPS, porta liberada). Em `config.yml`: `bridge.mode: "server"`. Por padrão escuta em `127.0.0.1:8080`.
Todas as rotas, exceto `health`, exigem `Authorization: Bearer <token>`.

| Método | Rota | |
|---|---|---|
| `POST` | `/api/v1/kits/request` | Pede uma entrega (espera o resultado final) |
| `GET` | `/api/v1/kits/status/{requestId}` | Consulta um pedido |
| `GET` | `/api/v1/kits` | Lista os kits |
| `GET` | `/api/v1/health` | Verifica se o plugin responde (sem autenticação) |

```bash
curl -X POST http://127.0.0.1:8080/api/v1/kits/request \
  -H "Authorization: Bearer SEU_TOKEN" -H "Content-Type: application/json" \
  -d '{"request_id":"discord-123456","player":"Elber","platform":"bedrock","kit":"VipGuloso"}'
```

```json
{ "success": true, "status": "KIT_DELIVERED", "final": true, "request_id": "discord-123456",
  "player": "Elber", "platform": "bedrock", "kit": "VipGuloso", "message": "Kit delivered successfully",
  "history": [ {"status":"QUEUED","at":0}, {"status":"LOADING_PLAYER","at":0}, "..." ] }
```

Jogador offline: HTTP 404 `{"success":false,"status":"PLAYER_OFFLINE","message":"Player not found"}`.

### Estados

`LOADING_PLAYER` (procurando) → `PLAYER_ON` (encontrado) → `LOADING_KIT` → `DELIVERING_KIT` → `KIT_DELIVERED`.
Também finais: `KIT_PENDING` (inventário cheio, guardado), e erros: `PLAYER_OFFLINE`, `PLATFORM_MISMATCH`, `PLATFORM_UNVERIFIABLE`, `KIT_NOT_FOUND`, `KIT_DISABLED`, `KIT_CORRUPT`, `INVENTORY_FULL`, `DELIVERY_DISABLED`, `DUPLICATE_REQUEST`, `INVALID_REQUEST`, `UNAUTHORIZED` (401), `RATE_LIMITED` (429), `TIMEOUT` (504), `INTERNAL_ERROR`.

## Segurança

- **Token** obrigatório, comparado em tempo constante. Nunca fica no código: vem de `config.yml` ou da variável `DKB_SECRET`. Tokens fracos (menos de 24 caracteres, vazio, `CHANGE_ME`) são recusados e substituídos por um gerado.
- **Nenhum comando arbitrário.** A única ação possível é "entregar um kit cadastrado". Campos extras no JSON (como `command`) são ignorados.
- **Validação rígida** de `request_id`, `player`, `platform` e `kit`. Nomes de kit não podem ser caminhos nem nomes reservados do Windows.
- **Rate limit** por IP (`security.rate-limit`, aplicado antes da autenticação para frear tentativas de adivinhar o token), limite de tamanho do corpo e **timeout** por pedido.
- A API (modo `server`) escuta só em `127.0.0.1` por padrão. Para expor, use **firewall e TLS**.
- Registros de log têm quebras de linha neutralizadas (sem forjar linhas).

## Logs

`plugins/DiscordKitBridge/logs/AAAA-MM-DD.log`, um arquivo por dia (apagados após `logs.retention-days`).
Eventos: `REQUEST_RECEIVED`, `PLAYER_IDENTIFIED`, `DELIVERED`, `PENDING`, `FAILED`, `DUPLICATE`, `AUTH_ERROR`, `RATE_LIMITED`, `INVALID_REQUEST`, `BOT_UNREACHABLE`, `OUTBOX_REJECTED`, `EVENTS_UNSUPPORTED`, `RECONCILED`, `ADMIN` (create/delete/reload/toggle), `CLAIM`.

## Arquivos de dados

```
plugins/DiscordKitBridge/
  config.yml
  kits/VipGuloso.json        um arquivo por kit
  data/requests.json         histórico (anti-duplicação)
  data/pending/*.json        kits pendentes por jogador (+ .bak; .corrupt-* se algo estragou)
  data/outbox.json           resultados/atualizações ainda não confirmados pelo bot
  logs/2026-11-13.log
```

Faça backup de `kits/` e `data/`.

## Solução de problemas

| Sintoma | Causa provável |
|---|---|
| `Segure uma Shulker Box na mão principal` | O item da mão não é Shulker Box (de qualquer cor). |
| `Essa Shulker Box está vazia` | Coloque os itens **dentro** da Shulker Box antes de `/dk create`. |
| Bot não recebe nada / `BOT_UNREACHABLE` no log | `bridge.poll.url` errada ou bot inacessível de fora. O plugin tenta de novo sozinho, com espera crescente. |
| `bot rejected the token` / `AUTH_ERROR` | O token do bot é diferente de `bridge.secret`. |
| `PLATFORM_UNVERIFIABLE` | Pedido Bedrock e Floodgate não está instalado. Instale o Floodgate. |
| `PLATFORM_MISMATCH` | Jogador online, mas na outra plataforma. Confira o que o cliente informou. |
| `PLAYER_OFFLINE` | O jogador não está conectado. Ele precisa entrar e o bot repetir o pedido com **novo** `request_id` (ou use `kits.require-online: false`). |
| Plugin não carrega | Versão do Java/Paper abaixo de 25 / 26.3. Veja o console. |
| API não abre (modo `server`) | Porta em uso, ou a hospedagem não permite portas de entrada: use `poll`. |

## Desenvolvimento

```bash
./scripts/run-tests.sh   # 100 testes, só JDK (sem servidor, sem Maven)
mvn package              # gera target/DiscordKitBridge-1.4.0.jar (JDK 25)
```

Veja `TESTING.md` para o roteiro de testes dentro do jogo.

## Kit Inicial (1.4.0)

Kit entregue automaticamente, **sem Shulker Box** (só os itens de dentro) e **sem passar pelo bot do Discord**.

1. Monte uma Shulker Box com os itens, segure-a e use `/dk create Kit_Inicial`.
2. Pronto: o jogador recebe o kit **uma única vez na primeira entrada** (ao entrar no servidor, ou ao entrar na sobrevivência pelo aldeão do LobbyGuard) e **toda vez que morre**. Clicar no aldeão de novo não dá mais nada.
3. Ajustes na seção `starter-kit` do `config.yml` (veja [CONFIG.md](CONFIG.md)). Teste na hora com `/dk starter [jogador]`.

O bot nunca enxerga esse kit: ele não aparece na lista sincronizada, e um pedido do Discord por esse nome responde "kit não encontrado".

## 🔗 Ecossistema Asthen

Este projeto faz parte do ecossistema **Asthen**, um conjunto de plugins e ferramentas que trabalham juntos para integrar o servidor Minecraft, o gerenciamento de jogadores, o lobby, os comandos, os sistemas internos e o Discord.

**Projetos relacionados:**

- [AsthenEssencials](https://github.com/ElbsxS1/AsthenEssencials)
- [DiscordKitBridge](https://github.com/ElbsxS1/DiscordKitBridge)
- [Lobby-Guard](https://github.com/ElbsxS1/Lobby-Guard)
- [BOT-ASTHEN](https://github.com/contatorzlkz-prog/BOT-ASTHEN)
