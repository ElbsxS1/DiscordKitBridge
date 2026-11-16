package br.asthen.kitbridge;

import br.asthen.kitbridge.api.ApiServer;
import br.asthen.kitbridge.api.OutboxStore;
import br.asthen.kitbridge.api.PollClient;
import br.asthen.kitbridge.bukkit.BukkitGame;
import br.asthen.kitbridge.bukkit.PlatformManager;
import br.asthen.kitbridge.commands.KitCommand;
import br.asthen.kitbridge.core.DeliveryEvent;
import br.asthen.kitbridge.core.DeliveryOptions;
import br.asthen.kitbridge.core.DeliveryService;
import br.asthen.kitbridge.core.RequestStore;
import br.asthen.kitbridge.kit.KitManager;
import br.asthen.kitbridge.kit.KitStorage;
import br.asthen.kitbridge.kit.PendingStore;
import br.asthen.kitbridge.listener.PlayerListener;
import br.asthen.kitbridge.logs.DeliveryLogger;
import br.asthen.kitbridge.security.Authentication;
import br.asthen.kitbridge.security.RateLimiter;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;

public final class DiscordKitBridge extends JavaPlugin {

    private volatile DeliveryOptions options;
    private DeliveryLogger logger;
    private KitManager kits;
    private RequestStore requests;
    private BukkitGame game;
    private DeliveryService service;
    private ApiServer api;
    private PollClient poll;
    private BukkitTask pollTask;
    private PendingStore pendingStore;
    private OutboxStore outbox;
    private PlayerListener listener;

    public KitManager kits() { return kits; }
    public DeliveryLogger logger() { return logger; }
    public BukkitGame game() { return game; }
    public DeliveryService service() { return service; }
    public PendingStore pendingStore() { return pendingStore; }
    public OutboxStore outbox() { return outbox; }
    public PollClient poll() { return poll; }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        options = readOptions();
        Path data = getDataFolder().toPath();

        logger = new DeliveryLogger(data.resolve("logs"), getConfig().getBoolean("logs.enabled", true),
                msg -> getLogger().info(msg));
        logger.purgeOlderThan(getConfig().getInt("logs.retention-days", 30));

        kits = new KitManager(new KitStorage(data.resolve("kits"), msg -> getLogger().warning(msg)),
                () -> { if (poll != null) poll.markKitsDirty(); });
        requests = new RequestStore(data.resolve("data").resolve("requests.json"), 5000,
                7L * 24 * 3600 * 1000, System::currentTimeMillis, msg -> getLogger().warning(msg));
        requests.load();

        PlatformManager platforms = new PlatformManager();
        game = new BukkitGame(platforms, () -> options, () -> getConfig().getBoolean("platform.allow-unverified-bedrock", false));
        pendingStore = new PendingStore(data.resolve("data").resolve("pending"), msg -> getLogger().severe(msg));
        PendingStore.Report rep = pendingStore.recover();
        getLogger().info("Pendências: " + rep.files() + " arquivo(s) verificado(s), " + rep.restoredFromBackup()
                + " restaurado(s) do backup, " + rep.quarantined() + " em quarentena, " + rep.interrupted() + " entrega(s) interrompida(s).");
        outbox = new OutboxStore(data.resolve("data").resolve("outbox.json"), System::currentTimeMillis,
                msg -> getLogger().warning(msg));
        if (outbox.size() > 0) getLogger().info("Outbox: " + outbox.size() + " resultado(s)/atualização(ões) ainda não confirmados pelo bot; serão reenviados.");
        service = new DeliveryService(game, kits, requests, pendingStore,
                logger, task -> Bukkit.getScheduler().runTask(this, task), () -> options);
        // later updates (KIT_PENDING -> DELIVERED) are written to the outbox first, then sent when the bot is reachable
        service.setEventSink(this::queueEvent);

        try {
            int n = kits.reload();
            getLogger().info(n + " kit(s) carregado(s).");
        } catch (IOException e) {
            getLogger().severe("Não foi possível ler a pasta de kits: " + e.getMessage());
        }

        PluginCommand cmd = getCommand("dk");
        if (cmd != null) {
            KitCommand kc = new KitCommand(this);
            cmd.setExecutor(kc);
            cmd.setTabCompleter(kc);
        }
        listener = new PlayerListener(this);
        Bukkit.getPluginManager().registerEvents(listener, this);

        getLogger().info(platforms.available()
                ? "Floodgate detectado: a plataforma Bedrock/Java será verificada por ele."
                : "Floodgate não encontrado: pedidos Bedrock só serão aceitos se platform.allow-unverified-bedrock for true.");

        startTransport();
    }

    /** Persists a delivery update, then nudges the transport. Called on the server thread; one small atomic write. */
    private void queueEvent(DeliveryEvent ev) {
        String json = br.asthen.kitbridge.util.Json.write(ev.toMap());
        PollClient p = poll;
        if (p != null) {
            p.enqueueEvent(ev.eventId(), ev.requestId(), json); // persisted on the outbox thread, then sent
            return;
        }
        try {
            outbox.add("event:" + ev.eventId(), OutboxStore.EVENT, ev.requestId(), json);
        } catch (IOException e) {
            getLogger().severe("Não foi possível guardar a atualização " + ev.eventId() + " na outbox: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (listener != null) listener.shutdown();
        stopTransport();
        if (requests != null) requests.close();
        if (logger != null) logger.close();
    }

    /** Re-reads config.yml and the kits folder and restarts the transport. @return number of kits loaded */
    public int reloadAll() throws IOException {
        reloadConfig();
        options = readOptions();
        stopTransport();
        int n = kits.reload();
        startTransport();
        return n;
    }

    // ------------------------------------------------------------------ config

    private DeliveryOptions readOptions() {
        FileConfiguration c = getConfig();
        return new DeliveryOptions(
                c.getBoolean("kits.allow-discord-delivery", true),
                c.getBoolean("kits.require-online", true),
                c.getBoolean("delivery.pending-if-inventory-full", true),
                c.getBoolean("delivery.prevent-duplicate", true),
                Math.max(1, c.getInt("security.request-timeout", 30)),
                c.getString("platform.floodgate-prefix", "."));
    }

    /** Env var DKB_SECRET wins; otherwise config; otherwise a strong token is generated and saved. */
    private String resolveSecret() {
        String env = System.getenv("DKB_SECRET");
        if (env != null && !env.isBlank()) {
            if (!Authentication.isWeak(env)) return env;
            getLogger().warning("A variável DKB_SECRET é fraca (mínimo " + Authentication.MIN_LENGTH + " caracteres) e foi ignorada.");
        }
        String s = getConfig().getString("bridge.secret", "");
        if (Authentication.isWeak(s)) {
            s = Authentication.generateToken();
            getConfig().set("bridge.secret", s);
            saveConfig();
            getLogger().warning("Um token seguro foi gerado em plugins/DiscordKitBridge/config.yml (bridge.secret). "
                    + "Copie-o para o seu bot e não o compartilhe.");
        }
        return s;
    }

    // ------------------------------------------------------------------ transport

    private void startTransport() {
        FileConfiguration c = getConfig();
        if (!c.getBoolean("bridge.enabled", true)) {
            getLogger().info("A integração com o Discord está desativada (bridge.enabled: false).");
            return;
        }
        String secret = resolveSecret();
        int timeout = options.requestTimeoutSeconds();
        String mode = c.getString("bridge.mode", "poll").toLowerCase(Locale.ROOT);

        if (mode.equals("server")) {
            String host = c.getString("bridge.server.host", "127.0.0.1");
            int port = c.getInt("bridge.server.port", 8080);
            if (!isLoopback(host)) {
                getLogger().warning("A API está escutando em " + host + ". Proteja com firewall e coloque TLS (proxy reverso) na frente: "
                        + "o token passa em texto puro sem TLS.");
            }
            api = new ApiServer(host, port, new Authentication(secret), new RateLimiter(c.getInt("security.rate-limit", 10)),
                    service, logger, timeout, c.getInt("security.max-body-bytes", 4096));
            try {
                api.start();
                getLogger().info("API REST ativa em http://" + host + ":" + port + "/api/v1");
            } catch (IOException e) {
                api = null;
                getLogger().severe("Não foi possível abrir a API em " + host + ":" + port + ": " + e.getMessage());
            }
            return;
        }

        String url = c.getString("bridge.poll.url", "");
        if (url.isBlank() || url.contains("SEU-BOT")) {
            getLogger().warning("bridge.poll.url não foi configurada: o plugin não vai buscar pedidos do bot. "
                    + "Edite config.yml e use /dk reload.");
            return;
        }
        try {
            URI u = URI.create(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) && !isPrivateHost(u.getHost())) {
                getLogger().warning("bridge.poll.url usa HTTP sem TLS para um endereço público. O token pode ser interceptado: use HTTPS.");
            }
        } catch (IllegalArgumentException e) {
            getLogger().severe("bridge.poll.url é inválida: " + e.getMessage());
            return;
        }
        int interval = Math.max(1, c.getInt("bridge.poll.interval-seconds", 3));
        boolean longPoll = c.getBoolean("bridge.poll.long-poll.enabled", false);
        int wait = Math.max(1, Math.min(25, c.getInt("bridge.poll.long-poll.wait-seconds", 20)));
        int httpTimeout = c.getInt("bridge.poll.http-timeout", longPoll ? 35 : 10);
        PollClient.Options po = new PollClient.Options(c.getInt("bridge.poll.max-per-poll", 5),
                c.getInt("bridge.poll.connect-timeout", 10), httpTimeout, longPoll, wait, interval);
        poll = new PollClient(url, secret, service, logger, outbox, po);
        PollClient p = poll;
        if (longPoll) {
            if (httpTimeout < wait + 5) {
                getLogger().warning("bridge.poll.http-timeout (" + httpTimeout + "s) é menor que long-poll.wait-seconds (" + wait
                        + "s) + 5; usando " + p.effectiveHttpTimeout().toSeconds() + "s.");
            }
            p.start();
            getLogger().info("Modo long polling: " + url + " (espera de até " + wait + "s por consulta, uma consulta por vez).");
        } else {
            long ticks = interval * 20L;
            pollTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, p::pollOnce, 40L, ticks);
            getLogger().info("Modo polling: consultando " + url + " a cada " + interval + "s.");
        }
    }

    private void stopTransport() {
        if (pollTask != null) {
            pollTask.cancel();
            pollTask = null;
        }
        if (poll != null) {
            poll.stop(); // cancels the waiting pull; unsent results stay in outbox.json
            poll = null;
        }
        if (api != null) {
            api.stop();
            api = null;
        }
    }

    private static boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isPrivateHost(String host) {
        if (host == null) return false;
        try {
            InetAddress a = InetAddress.getByName(host);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (IOException e) {
            return false;
        }
    }
}
