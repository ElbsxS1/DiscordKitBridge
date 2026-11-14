'use strict';
// Usado pelo teste ponta a ponta (TestMain): sobe o bot de verdade e pede uma entrega.
const { startBridge } = require('../bot-integration/asthen-bridge');

const bridge = startBridge({
  token: process.env.DKB_SECRET,
  port: 0,
  host: '127.0.0.1',
  onKits: (k) => console.log('KITS ' + JSON.stringify(k)),
});

bridge.listening
  .then((port) => {
    console.log('PORT ' + port);
    return bridge.deliverKit({ requestId: 'e2e-1', player: 'Elber', platform: 'bedrock', kit: 'VipGuloso', timeoutMs: 20000 });
  })
  .then((r) => {
    console.log('RESULT ' + JSON.stringify(r));
    return bridge.close();
  })
  .then(() => process.exit(0))
  .catch((e) => {
    console.log('ERROR ' + e.message);
    process.exit(1);
  });
