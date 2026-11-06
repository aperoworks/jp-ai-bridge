package com.aperogames.jpaibridge.forge;

import com.aperogames.jpaibridge.core.BridgeConfig;
import com.aperogames.jpaibridge.core.BridgeServer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Forge entry point. Loads config/jpaibridge.properties and starts the local bridge.
 * Works on client and dedicated server (the side that talks to the AI API needs it).
 */
@Mod(JpAiBridgeMod.MOD_ID)
public final class JpAiBridgeMod {

    public static final String MOD_ID = "jpaibridge";
    private static final Logger LOGGER = LoggerFactory.getLogger("JP AI Bridge");
    private static BridgeServer server;

    public JpAiBridgeMod() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("jpaibridge.properties");
        try {
            BridgeConfig config = BridgeConfig.load(file);
            if (!config.enabled) {
                LOGGER.info("JP AI Bridge is disabled in {}", file);
                return;
            }
            if (config.chatKey.isBlank()) {
                LOGGER.warn("JP AI Bridge: chat.key is empty in {} (requests will be forwarded without a key)", file);
            }
            server = new BridgeServer(config, LOGGER::info);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "jp-ai-bridge-stop"));
        } catch (Exception e) {
            LOGGER.error("JP AI Bridge failed to start (config: {})", file, e);
        }
    }
}
