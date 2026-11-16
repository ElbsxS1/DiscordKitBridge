# Configuração (`plugins/DiscordKitBridge/config.yml`)

Depois de editar: `/dk reload` (o `logs.enabled` exige reiniciar).

| Chave | Padrão | O que faz |
|---|---|---|
| `bridge.enabled` | `true` | Liga a integração com o bot |
| `bridge.mode` | `poll` | `poll` (o plugin liga para o bot; só conexões de saída) ou `server` (API REST; exige porta aberta) |
| `bridge.secret` | vazio | Mesmo valor do `DKB_SECRET` do bot. **Só o valor.** Vazio = gera um; a variável de ambiente `DKB_SECRET` tem prioridade |
| `bridge.poll.url` | placeholder | URL HTTPS pública do bot, sem `/` no final e sem `/plugin/v1` |
| `bridge.poll.interval-seconds` | `3` | Intervalo do modo tradicional; pausa mínima se o bot responder vazio na hora |
| `bridge.poll.max-per-poll` | `5` | Máximo de pedidos por consulta |
| `bridge.poll.connect-timeout` | `10` | Segundos para conectar |
| `bridge.poll.http-timeout` | `35` | Segundos de espera pela resposta. Sempre maior que `wait-seconds` (corrigido sozinho) |
| `bridge.poll.long-poll.enabled` | `true` (config nova) / `false` (chave ausente) | Long polling. Exige bot 2.12.0+ |
| `bridge.poll.long-poll.wait-seconds` | `20` | Espera por consulta (1 a 25) |
| `bridge.server.host` / `port` | `127.0.0.1` / `8080` | Modo `server` |
| `platform.floodgate-prefix` | `.` | Prefixo dos nomes Bedrock |
| `platform.allow-unverified-bedrock` | `false` | Aceitar Bedrock sem Floodgate (inseguro) |
| `kits.allow-discord-delivery` | `true` | Aceita pedidos do bot |
| `kits.require-online` | `true` | `false` = guarda para quem está offline e entrega ao entrar |
| `delivery.pending-if-inventory-full` | `true` | Inventário cheio guarda o kit inteiro (auto-claim, `/dk claim` ou ao entrar) |
| `delivery.prevent-duplicate` | `true` | Recusa pedido idêntico (mesma plataforma + jogador + kit) em andamento |
| `security.rate-limit` | `10` | Chamadas por minuto por IP (modo `server`) |
| `security.request-timeout` | `30` | Segundos até um pedido não processado virar `TIMEOUT` |
| `security.max-body-bytes` | `4096` | Tamanho máximo do corpo (modo `server`) |
| `logs.enabled` / `retention-days` | `true` / `30` | Logs diários |
