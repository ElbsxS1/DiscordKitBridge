# Migração 1.2.0 → 1.3.0

Nenhum dado é apagado ou convertido à força.

1. Faça backup de `plugins/DiscordKitBridge/kits/` e `data/`.
2. Troque o `.jar` por `DiscordKitBridge-1.3.0.jar` (remova o antigo) e reinicie.
3. Arquivos antigos de `data/pending/` são lidos normalmente (sem `state` = `PENDING`) e passam ao formato novo na próxima gravação.
4. Seu `config.yml` **não é sobrescrito**. As chaves novas não existem nele; sem elas o plugin se comporta como a 1.2.0 (long polling desligado). Para ligar, acrescente:

```yaml
bridge:
  poll:
    connect-timeout: 10
    http-timeout: 35
    long-poll:
      enabled: true
      wait-seconds: 20
```
   e rode `/dk reload`. O console deve mostrar "Modo long polling".
5. `bridge.secret` deve ter **só o valor** do `DKB_SECRET`, sem `DKB_SECRET=`.
6. Para jogador offline: `kits.require-online: false`.

## Permissões novas

| Permissão | Comandos | Em `discordkitbridge.admin`? |
|---|---|---|
| `discordkitbridge.toggle` | `/dk enable`, `/dk disable`, `/dk toggle` | sim |
| `discordkitbridge.reload` | `/dk reload` | sim |
| `discordkitbridge.pending` | `/dk pending` | sim |

Quem recebia `enable/disable/toggle/reload` só por `discordkitbridge.admin` continua recebendo (admin inclui as novas). Quem
tinha só uma permissão solta (por exemplo `discordkitbridge.list`) **não** ganha mais sugestões de outros comandos.

## Bot

O bot 2.12.0 funciona com o plugin 1.3.0. Para o Discord mostrar "entregue" depois que o jogador libera espaço, o bot
precisa do endpoint `POST /plugin/v1/events`: veja [BOT-INTEGRATION-1.3.0.md](BOT-INTEGRATION-1.3.0.md). Sem ele, o
plugin guarda as atualizações e tenta a cada 10 minutos, sem perder nenhuma.
