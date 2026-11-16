# Pendências, auto-claim e recuperação

## Estados (salvos em `data/pending/*.json`)

| Estado | Significado |
|---|---|
| `PENDING` | Esperando o jogador (offline ou inventário cheio). |
| `CLAIMING` | Uma entrega começou. Gravado **antes** de dar os itens. |
| `DELIVERED` | Entregue (só existe por instantes; a pendência é removida). |
| `RETRY` | Tentativa falhou sem entregar nada (inventário cheio, jogador saiu): tenta de novo. |
| `ERROR` | Precisa de uma pessoa (kit ilegível, por exemplo). |

## Ordem de uma entrega

1. grava `CLAIMING` (se não conseguir gravar, **não entrega nada**);
2. dá a Shulker Box (tudo ou nada);
3. remove a pendência do disco; se a remoção falhar, lembra em memória que **já entregou** e tenta limpar depois.

## Não existe transação entre o arquivo e o inventário

Uma queda exatamente entre os passos 2 e 3 deixa a pendência em `CLAIMING`. No próximo início o plugin **não sabe** se o
jogador recebeu. Ele não entrega de novo sozinho. Veja `/dk pending` e decida:

- o jogador **tem** a caixa: `/dk pending resolve <request_id> delivered`;
- o jogador **não tem**: `/dk pending resolve <request_id> retry`.

## Arquivo ilegível

1. o arquivo ruim é movido para `*.corrupt-<hora>` (nunca apagado);
2. se existir `*.json.bak`, ele é restaurado; senão a pendência daquele jogador precisa ser recuperada à mão a partir do `.corrupt-*`;
3. o console mostra a linha `[pending] ...` e `/dk pending` lista o problema.

O `.bak` é a versão anterior à última gravação. Uma restauração pode trazer de volta uma entrega já feita; o plugin confere o histórico (`requests.json`) e **não reentrega** o que consta como `KIT_DELIVERED`. Uma pendência recente que ficou fora do backup aparece em `/dk pending` como "órfã" (o bot foi avisado de `KIT_PENDING`, mas não há pendência guardada).

## Inicialização

Carrega kits → valida e recupera `data/pending` → abre a outbox → mostra no console quantos arquivos foram restaurados, postos em quarentena ou estavam interrompidos.

## Auto-claim

Gatilhos: fechar inventário, soltar item, clicar no inventário. Regras: só roda se o jogador tem pendência (consulta em memória); no máximo uma tentativa agendada por jogador, no tick seguinte; intervalo de 750 ms entre tentativas; uma tentativa nunca agenda outra; aviso de inventário cheio no máximo a cada 30 s; `/dk claim` continua existindo.

## Jogador offline (`kits.require-online: false`)

A pendência nasce com chave por **plataforma + nome** (`name:java:bia`). Quando o jogador entra, o plugin a re-arquiva sob o **UUID** e entrega. Um Bedrock com o mesmo nome base não recebe o kit pedido para o Java. Depois de ligada ao UUID, uma troca de nick não importa.
