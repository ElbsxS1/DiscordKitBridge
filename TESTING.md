# Roteiro de testes no servidor

`./scripts/run-tests.sh` já cobre (67 testes automáticos) o cadastro e a persistência dos kits, a segurança,
a duplicidade, o pipeline de entrega, a API HTTP e a conexão com o bot.

A **camada fina do Paper** (ler a Shulker Box, mexer no inventário, Floodgate, comandos) só pode ser
verificada num servidor Paper 26.3 de verdade. Faça este roteiro uma vez e marque.

## Preparação
- [ ] Servidor Paper 26.3 com o `.jar` em `plugins/`. O console mostra `N kit(s) carregado(s)` e nenhum erro.
- [ ] Sem Floodgate: o console avisa *Floodgate não encontrado*. Com Floodgate: *Floodgate detectado*.
- [ ] `config.yml` tem `bridge.secret` preenchido (gerado) e `bridge.poll.url` configurada.

## Cadastro
- [ ] Monte uma Shulker Box com: espada encantada (Afiação V) com nome e lore, ferramenta danificada (durabilidade parcial), pilha de 64, item com nome personalizado, e algo de cada categoria que você usa.
- [ ] Sem nada na mão: `/dk create Teste` → erro *Segure uma Shulker Box*.
- [ ] Com um bloco comum na mão: mesmo erro.
- [ ] Com uma Shulker Box **vazia**: erro *está vazia*.
- [ ] Com a Shulker Box cheia: `/dk create VipGuloso` → `[DiscordKitBridge] Kit VipGuloso cadastrado com sucesso!`.
- [ ] `/dk create vipguloso` → recusado (nome duplicado).
- [ ] `/dk create ../x` e `/dk create a b` → nome inválido.
- [ ] Existe `plugins/DiscordKitBridge/kits/VipGuloso.json`.
- [ ] Como jogador sem permissão: `/dk create` e `/dk list` → sem permissão.

## Visualização e persistência
- [ ] `/dk list` mostra o kit. `/dk info VipGuloso` mostra itens, data e status.
- [ ] `/dk preview VipGuloso` abre a janela; **não** dá para pegar, mover nem soltar itens (clique, shift-clique, arrastar).
- [ ] Os itens do preview têm encantamento, nome, lore e durabilidade idênticos aos originais.
- [ ] **Reinicie o servidor.** `/dk list` e `/dk preview` continuam iguais.
- [ ] Atualizar o `.jar` (reinício) mantém os kits.

## Preço e ativar/desativar
- [ ] `/dk price VipGuloso 29,90` → `Preço do kit VipGuloso: R$ 29,90.` e `/dk info VipGuloso` mostra o preço. `/dk list` mostra o preço de cada kit.
- [ ] `/dk price VipGuloso 45` muda o preço **sem** apagar o kit; `/dk preview VipGuloso` continua igual.
- [ ] Valores inválidos (`0`, `-5`, `abc`, `29,999`) → *Preço inválido*.
- [ ] `/dk price VipGuloso remover` → kit sem preço (`/dk info` mostra *não definido*).
- [ ] `/dk disable VipGuloso` → desativado; `/dk enable VipGuloso` → ativo. Repetir o mesmo comando avisa que já está nesse estado.
- [ ] Reiniciar o servidor: preço e status continuam.
- [ ] Jogador sem permissão: `/dk price`, `/dk enable`, `/dk disable` → sem permissão.

## Entrega direta (sem o bot)
- [ ] `/dk give VipGuloso` entrega **uma Shulker Box** com o nome do kit. Segure, coloque no chão e abra: os itens dentro são idênticos ao preview, cada um no seu slot.
- [ ] A **cor** da Shulker Box entregue é a mesma da usada no `/dk create` (teste com uma azul, por exemplo).
- [ ] Um kit cadastrado antes desta versão (sem cor guardada) sai em Shulker Box **roxa**.
- [ ] Com **um único slot livre**, o kit entra. Com o inventário 100% cheio, não entra e fica pendente.
- [ ] `/dk give VipGuloso OutroJogador` entrega ao outro jogador (e ele é avisado).
- [ ] Com o inventário cheio: o kit **não** entra, fica pendente; libere espaço, `/dk claim` entrega o kit inteiro, uma vez.
- [ ] Como jogador sem permissão: `/dk give` → sem permissão.

## Entrega pelo bot (use o bot, ou `curl` no modo `server`)
- [ ] Jogador **Java** online: kit entregue; os itens são idênticos ao preview.
- [ ] Jogador **Bedrock** online (Geyser+Floodgate): kit entregue.
- [ ] Pedir `bedrock` para um jogador Java com o mesmo nome base → `PLATFORM_MISMATCH`, nada entregue.
- [ ] Sem Floodgate, pedir `bedrock` → `PLATFORM_UNVERIFIABLE`.
- [ ] Jogador offline → `PLAYER_OFFLINE`.
- [ ] Kit inexistente → `KIT_NOT_FOUND`.
- [ ] `/dk toggle VipGuloso` e pedir → `KIT_DISABLED`; `/dk toggle` de novo reativa.
- [ ] **Inventário cheio** (36 slots ocupados): resposta `KIT_PENDING`, o jogador é avisado, **nenhum item** entra. Libere espaço, `/dk claim` → recebe o kit **inteiro**, uma vez. `/dk claim` outra vez → *sem kits pendentes*.
- [ ] Inventário **quase** cheio (cabe parte do kit): também fica pendente inteiro, sem entrega parcial.
- [ ] Enviar o **mesmo `request_id`** duas vezes → entregue **uma** vez só.
- [ ] Entregar, reiniciar o servidor, reenviar o mesmo `request_id` → continua uma vez só.
- [ ] Jogador com pendência sai e entra → recebe (se houver espaço) ao entrar.

## Segurança e falhas
- [ ] Token errado no bot → `AUTH_ERROR` no log, nada entregue.
- [ ] Desligar o bot → `BOT_UNREACHABLE` no log, servidor sem lag; ligar de novo → volta sozinho.
- [ ] `/dk delete VipGuloso` pede confirmação; `/dk delete VipGuloso confirm` remove; o arquivo some.
- [ ] `/dk reload` recarrega sem erro.
- [ ] `logs/AAAA-MM-DD.log` registra recebido, identificado, entregue/falha e operações administrativas.
- [ ] Sem lag perceptível no servidor durante as consultas (TPS normal).
