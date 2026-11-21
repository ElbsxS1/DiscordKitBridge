# Changelog

## 1.4.0

### Novo: Kit Inicial
- Kit especial **`Kit_Inicial`**, entregue pelo próprio plugin **sem a Shulker Box** (só os itens de dentro vão direto para o inventário).
- Entregue **na primeira vez** do jogador (ao entrar no servidor ou ao entrar na sobrevivência pelo aldeão do LobbyGuard, o que vier primeiro) e **toda vez que ele morre** (depois de renascer). O aldeão não dá mais nada a quem já recebeu: sem spam. Quem já recebeu fica salvo em `data/starter-received.txt` (um UUID por linha) e sobrevive a reinícios.
- A entrada pelo aldeão é detectada por `PlayerChangedWorldEvent` (lobby → survival); o LobbyGuard não precisa ser alterado.
- **Nunca vai para o bot do Discord**: fica fora da lista de kits sincronizada com o bot, um pedido do Discord por esse nome responde `KIT_NOT_FOUND` (igual a um kit inexistente, nada é entregue nem guardado como pendente) e nenhum evento/resultado sobre ele entra na outbox. O único rastro é uma linha no log local (`STARTER_KIT`).
- Cadastro: segure uma Shulker Box com os itens e use `/dk create Kit_Inicial`.
- `/dk starter [jogador]` entrega na hora, para testar (permissão `discordkitbridge.give`).
- Seção nova `starter-kit` no `config.yml` (ligar/desligar cada gatilho, nome, mensagem, cooldown, mundos). Veja `CONFIG.md`.
- Cooldown por jogador (`cooldown-seconds`, padrão 10 s) evita kit duplo quando dois gatilhos disparam juntos.
- Inventário cheio: o que não couber cai aos pés do jogador e só ele pode pegar (`drop-if-full`).
- `starter-kit.first-time-only: false` volta a entregar a cada entrada (e libera o spam do aldeão).
- 117 testes automáticos (eram 100).

### Mudanças de comportamento
- O nome do Kit Inicial fica **reservado**: mesmo com `starter-kit.enabled: false` o bot continua sem enxergá-lo.
- Ao entrar no servidor, quem está no mundo `lobby` **não** recebe o kit (`on-join.skip-worlds`): ele vem ao clicar no aldeão. Para entregar também no lobby, deixe a lista vazia.

### Limitações conhecidas
- Para dar o kit de novo a alguém que só o recebeu pela "primeira vez", apague a linha do UUID dele em `data/starter-received.txt` (com o servidor parado) ou use `/dk starter <jogador>`.
- Quem morre sempre recebe o kit: se a morte for muito fácil de repetir de propósito, o kit pode ser coletado assim. O `cooldown-seconds` só segura mortes em sequência imediata.
- Os testes automáticos não executam o Paper: os eventos e o inventário precisam de teste no servidor (ver `TESTING.md`).

## 1.3.0

### Corrigido
- **Pendências**: um arquivo de pendências ilegível era lido como "lista vazia". Agora é restaurado do backup (`.bak`) ou guardado como `.corrupt-<hora>`, com log e aviso em `/dk pending`. Nada é apagado sem confirmação de gravação.
- **Entrega duplicada**: se a gravação da remoção falhasse depois de entregar a Shulker Box, a mesma caixa podia ser entregue de novo. Agora o estado `CLAIMING` é gravado **antes** de entregar, a remoção é refeita e uma entrega já feita nunca é repetida.
- **Histórico de pedidos** (`requests.json`) ilegível também é guardado ao lado em vez de ser substituído em silêncio.
- A proteção contra pedido idêntico em andamento agora considera a **plataforma**: Java e Bedrock com nome parecido não se confundem.
- 401/403 na sincronização de kits agora aparece como `AUTH_ERROR`.
- Permissões: `/dk enable|disable|toggle` usam `discordkitbridge.toggle`, `/dk reload` usa `discordkitbridge.reload`; o Tab de cada comando usa a permissão do próprio comando.

### Novo
- Estados persistentes de pendência: `PENDING`, `CLAIMING`, `DELIVERED`, `RETRY`, `ERROR`; recuperação na inicialização; entregas interrompidas **não** são refeitas sozinhas.
- `/dk pending` e `/dk pending resolve <request_id> <delivered|retry>` (permissão `discordkitbridge.pending`).
- **Long Polling** (`bridge.poll.long-poll`), uma consulta por vez, backoff progressivo, timeout HTTP sempre maior que a espera. Modo tradicional preservado.
- **Outbox persistente** (`data/outbox.json`): resultados e atualizações só saem da fila quando o bot confirma (`200 {"success":true}`).
- **Atualização posterior de status** (`KIT_PENDING` → `KIT_DELIVERED`) enviada a `POST /plugin/v1/events`. **O bot ainda precisa implementar este endpoint**: ver `docs/BOT-INTEGRATION-1.3.0.md`. Enquanto isso, os eventos ficam guardados.
- **Auto-claim**: tenta entregar no tick seguinte quando o jogador libera espaço, com debounce e sem laços.
- Pendência de jogador offline é ligada ao UUID ao entrar.
- Pedidos `KIT_PENDING` sem pendência guardada (por exemplo após restaurar um backup antigo) aparecem em `/dk pending`.
- 100 testes automáticos (eram 67).

### Mudanças de comportamento
- Reenvio de resultados usa espera progressiva (2 s, 4 s ... até 5 min) em vez de tentar a cada consulta.
- Config nova: `bridge.poll.connect-timeout`, `bridge.poll.long-poll.enabled`, `bridge.poll.long-poll.wait-seconds`. Em arquivos antigos sem essas chaves, o long polling fica **desligado** (comportamento da 1.2.0).

### Limitações conhecidas
- Não existe transação entre o arquivo e o inventário do Minecraft. Uma queda exatamente entre dar os itens e gravar o resultado deixa a pendência em `CLAIMING`, que exige decisão de um administrador. Isso evita duplicar, ao custo de uma decisão manual rara.
- A cópia de backup (`.bak`) fica uma gravação atrás; uma restauração pode trazer de volta uma pendência já entregue (o histórico de pedidos impede a reentrega) ou perder a mais recente (aparece como órfã em `/dk pending`).
- Os testes automáticos não executam o Paper: Floodgate, Shulker Box e eventos do Bukkit precisam de teste no servidor (ver `TESTING.md`).

## 1.2.0
Preço, enable/disable e entrega em Shulker Box.
