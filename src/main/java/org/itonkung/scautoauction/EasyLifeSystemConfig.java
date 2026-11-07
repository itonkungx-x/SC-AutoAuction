package org.itonkung.scautoauction;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Per-module state; older activeSystem configs are migrated once. */
public final class EasyLifeSystemConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("easylife-systems.json");
    private static volatile EasyLifeSystemConfig current = new EasyLifeSystemConfig();

    public boolean auctionEnabled = true;
    public boolean priceExtractorEnabled = false;
    public boolean extractorNotifications = true;
    /** Only acts inside verified WORTH (page/total) menus. */
    public boolean autoScanWorthPages = false;
    /** auto = inspect visible /worth listings; hover = capture only hovered item; both = both pipelines. */
    public String priceScanMode = "both";
    public int extractorPort = 8787;
    /** Easylife-specific UI language. Independent of Minecraft client language. */
    public String uiLanguage = "th_th";
    /** Legacy single-mode selection retained for migration only. */
    public String activeSystem = "auction";

    public static EasyLifeSystemConfig get() { return current; }

    public static synchronized void load() {
        if (!Files.isRegularFile(PATH)) return;
        try {
            String raw = Files.readString(PATH, StandardCharsets.UTF_8);
            JsonObject data = JsonParser.parseString(raw).getAsJsonObject();
            EasyLifeSystemConfig cfg = JSON.fromJson(data, EasyLifeSystemConfig.class);
            if (cfg == null) return;
            if (!data.has("auctionEnabled") && !data.has("priceExtractorEnabled")) {
                cfg.priceExtractorEnabled = "price_extractor".equals(cfg.activeSystem);
                cfg.auctionEnabled = !cfg.priceExtractorEnabled;
            }
            cfg.validate();
            current = cfg;
        } catch (Exception e) {
            System.err.println("[Easylife] Unable to read settings: " + e);
        }
    }

    public static synchronized void save(EasyLifeSystemConfig cfg) {
        cfg.validate();
        try {
            Files.createDirectories(PATH.getParent());
            Path temp = Files.createTempFile(PATH.getParent(), "easylife-systems-", ".json.tmp");
            try {
                Files.writeString(temp, JSON.toJson(cfg), StandardCharsets.UTF_8);
                Files.move(temp, PATH, StandardCopyOption.REPLACE_EXISTING);
                current = cfg.copy();
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot save systems configuration", e);
        }
    }

    public EasyLifeSystemConfig copy() {
        EasyLifeSystemConfig c = new EasyLifeSystemConfig();
        c.auctionEnabled = auctionEnabled;
        c.priceExtractorEnabled = priceExtractorEnabled;
        c.extractorNotifications = extractorNotifications;
        c.autoScanWorthPages = autoScanWorthPages;
        c.priceScanMode = priceScanMode;
        c.extractorPort = extractorPort;
        c.uiLanguage = uiLanguage;
        c.activeSystem = activeSystem;
        return c;
    }

    public void validate() {
        if (!"auto".equals(priceScanMode) && !"hover".equals(priceScanMode) && !"both".equals(priceScanMode)) priceScanMode = "both";
        if (!"en_us".equals(uiLanguage)) uiLanguage = "th_th";
        if (extractorPort < 1024 || extractorPort > 65535) {
            throw new IllegalArgumentException("พอร์ตเว็บต้องอยู่ระหว่าง 1024–65535");
        }
    }
}
