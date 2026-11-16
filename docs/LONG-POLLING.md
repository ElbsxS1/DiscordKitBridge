# Long Polling

O plugin pergunta ao bot "tem pedido?" e o bot **segura a resposta** até chegar um pedido ou o tempo acabar.

```yaml
bridge:
  poll:
    interval-seconds: 3
    max-per-poll: 5
    connect-timeout: 10
    http-timeout: 35
    long-poll:
      enabled: true
      wait-seconds: 20
```

- `GET /plugin/v1/pull?limit=5&wait_ms=20000`. Resposta `200` com pedidos, ou `204` ao fim da espera (normal, **não é erro**).
- **Uma consulta por vez**, numa thread própria (`DiscordKitBridge-LongPoll`). Nunca na thread do servidor.
- Depois de qualquer resposta válida, pergunta de novo na hora.
- `http-timeout` é sempre maior que a espera: se você configurar menos que `wait-seconds + 10`, o plugin usa esse valor e avisa no console. O bot limita a espera em 25 s.
- Falha (bot fora do ar, 5xx, 401): espera progressiva 2 s, 4 s, 8 s ... até 60 s, com um pouco de aleatoriedade. Log `BOT_UNREACHABLE` na 1ª falha e a cada 10.
- Bot que ignora `wait_ms` e responde `204` na hora: o plugin espera `interval-seconds` entre as consultas.
- `/dk reload` ou desligar o servidor cancela a consulta em espera e encerra as threads. Resultados não enviados ficam em `data/outbox.json`.
- `long-poll.enabled: false` (ou ausente num `config.yml` antigo) = modo tradicional da 1.2.0: uma consulta a cada `interval-seconds`, sem `wait_ms`.
