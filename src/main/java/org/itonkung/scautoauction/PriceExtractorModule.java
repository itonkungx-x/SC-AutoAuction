package org.itonkung.scautoauction;

import com.google.gson.Gson;
import com.mojang.blaze3d.platform.InputConstants;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * /worth price scanner.
 *
 * Important: data sent by servers can become visible only after Minecraft
 * assembles the display tooltip. Do not rely only on DataComponents.LORE.
 * Read actual vanilla/container tooltip with an item-lore fallback.
 */
public final class PriceExtractorModule implements AutomationModule {
    private static final KeyMapping TOGGLE = KeyMappingHelper.registerKeyMapping(new KeyMapping(
        "key.scautoauction.extractor", InputConstants.Type.KEYBOARD, InputConstants.KEY_F7, KeyMapping.Category.MISC));
    private static final Gson JSON = new Gson();
    private static final int MAX_ITEMS = 2048;
    private static final Map<String, PriceEntry> ENTRIES = new LinkedHashMap<>();

    private static volatile String lastItem = "ยังไม่พบข้อมูล";
    private static volatile String lastJson = "{}";
    private static volatile String lastError = "";
    private static volatile String statusTh = "ยังไม่เริ่ม";
    private static volatile String statusEn = "Not started";
    private static volatile int itemCount, lastScanned, lastPriceLines;
    private static volatile int observedTooltipCalls, observedTooltipPrices;
    private static volatile int lastTooltipLines;
    private static volatile String lastDetectedTitle = "-";
    private static volatile String lastDiagnosticFile = "-";

    private HttpServer server;
    private int serverPort = -1;
    private int ticks, emptyRounds, lastMenuId = Integer.MIN_VALUE;
    private int missingSlotsRounds;
    private int lastReportedMenuId = Integer.MIN_VALUE;
    private volatile boolean diagnosticSavedForMenu;
    private static volatile String selectedMenuSource = "-";
    private static volatile int screenMenuSize, playerMenuSize;
    private String screenTitle = "";
    private boolean warnedNoPrice;
    private Method containerTooltipMethod;
    private boolean tooltipLookupDone;
    private Field hoveredSlotField;
    private boolean hoveredSlotLookupDone;
    private static long lastShortcutNanos;

    public static int getFoundCount() { return itemCount; }
    public static String getLastFoundName() { return lastItem; }
    public static String getStatus() { return lastError.isBlank() ? statusTh : lastError; }
    public static String getStatusForLanguage(String language) {
        String status = "en_us".equals(language) ? statusEn : statusTh;
        return lastError.isBlank() ? status : lastError;
    }
    public static String getDiagnostics() {
        // Compact: the Settings screen intentionally truncates strings > 62 chars.
        return "GUI " + screenMenuSize + " | Inv " + playerMenuSize
            + " | Item " + lastScanned + " | Price " + lastPriceLines
            + " | Hover " + observedTooltipCalls + "/" + observedTooltipPrices;
    }

    public static synchronized void clearItems() {
        ENTRIES.clear();
        itemCount = 0;
        lastItem = "ยังไม่พบข้อมูล";
        lastJson = "{}";
        lastPriceLines = 0;
        lastScanned = 0;
        screenMenuSize = 0;
        playerMenuSize = 0;
        selectedMenuSource = "-";
        observedTooltipCalls = 0;
        observedTooltipPrices = 0;
        lastTooltipLines = 0;
        lastDiagnosticFile = "-";
        statusTh = "ล้างข้อมูลแล้ว";
        statusEn = "Scan results cleared";
    }

    @Override public String id() { return "price_extractor"; }
    @Override public void initialize() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) ->
            observeRenderedTooltip(stack, lines));
        // KeyMapping.consumeClick() is normally not populated while a screen is open.
        // Listen for the registered F7 (or remapped) key inside the /worth container.
        ScreenEvents.AFTER_INIT.register((client, current, w, h) -> {
            if (!(current instanceof AbstractContainerScreen<?>)) return;
            if (!current.getTitle().getString().toLowerCase(Locale.ROOT).contains("worth")) return;
            ScreenKeyboardEvents.allowKeyPress(current).register((scr, event) -> {
                if (!TOGGLE.matches(event)) return true;
                toggleEnabled(client);
                return false; // handled: prevent container shortcut conflicts
            });
        });
    }
    @Override public void stop(Minecraft mc) { stopServer(); }

    @Override
    public void tick(Minecraft mc) {
        while (TOGGLE.consumeClick()) toggleEnabled(mc);
        if (!EasyLifeSystemConfig.get().priceExtractorEnabled) {
            if (server != null) stopServer();
            statusTh = "ระบบปิดอยู่";
            statusEn = "Scanner disabled";
            return;
        }
        ensureServer();
        if (++ticks % 4 != 0 || mc == null || mc.player == null || mc.gui == null) return;
        Screen current = mc.gui.screen();
        if (!(current instanceof AbstractContainerScreen<?> screen)) {
            // Keep the last scan's result visible in Mod Menu after closing /worth.
            if (isWorthTitle(lastDetectedTitle)) {
                statusTh = "ผล /worth ล่าสุด: ช่อง " + lastScanned
                    + " • มีราคา " + lastPriceLines + " • รวม " + itemCount;
                statusEn = "Last /worth: items " + lastScanned
                    + ", priced " + lastPriceLines + ", saved " + itemCount;
            } else {
                statusTh = "รอเปิดหน้า /worth";
                statusEn = "Open /worth to scan prices";
            }
            emptyRounds = 0;
            return;
        }
        String title = screen.getTitle().getString();
        lastDetectedTitle = title;
        if (!isWorthTitle(title)) {
            statusTh = "รอเปิดหน้า /worth (" + title + ")";
            statusEn = "Waiting for /worth (" + title + ")";
            emptyRounds = 0;
            return;
        }

        String scanMode = EasyLifeSystemConfig.get().priceScanMode;
        if (!"auto".equals(scanMode)) scanHoveredSlot(mc, screen);

        if ("hover".equals(scanMode)) {
            statusTh = "Hover เท่านั้น • จับได้ " + observedTooltipCalls
                + " ครั้ง • ราคา " + observedTooltipPrices + " • สะสม " + itemCount;
            statusEn = "Hover only • Tooltips " + observedTooltipCalls
                + " • Priced " + observedTooltipPrices + " • Saved " + itemCount;
            return;
        }
        // Automatic scanning every 12 ticks, hover sampling every 4 ticks.
        if (ticks % 12 != 0) return;

        // Some custom inventory screens expose a different container than
        // the player's active server menu. Compare both before declaring 0 slots.
        AbstractContainerMenu screenMenu = screen.getMenu();
        AbstractContainerMenu activeMenu = mc.player.containerMenu;
        List<Slot> screenSlots = screenMenu.slots;
        List<Slot> activeSlots = activeMenu == null ? List.of() : activeMenu.slots;
        screenMenuSize = screenSlots.size();
        playerMenuSize = activeSlots.size();
        int onScreen = filledListingSlots(screenSlots);
        int inPlayerMenu = filledListingSlots(activeSlots);
        boolean useActive = !activeSlots.isEmpty()
            && (screenSlots.isEmpty()
                || (activeMenu.containerId == screenMenu.containerId
                    && inPlayerMenu > onScreen));
        List<Slot> slots = useActive ? activeSlots : screenSlots;
        selectedMenuSource = useActive ? "player.containerMenu" : "screen.getMenu()";
        int menuId = screenMenu.containerId;
        if (menuId != lastMenuId || !title.equals(screenTitle)) {
            emptyRounds = 0;
            warnedNoPrice = false;
            lastMenuId = menuId;
            screenTitle = title;
            diagnosticSavedForMenu = false;
            missingSlotsRounds = 0;
            say(mc, "ตรวจพบหน้า " + title + " • ช่อง GUI " + screenMenuSize
                + " • ช่อง Player " + playerMenuSize + " • กำลังอ่านราคา");
        }

        // The last 36 slots represent player inventory in chest-like menus.
        // Most chest-like GUI menus reserve the last 36 slots for player inventory.
        // If the menu only has 36 inventory slots, there are no visible sale listings.
        int topSlots = slots.size() > 36 ? slots.size() - 36 : 0;
        if (topSlots == 0) {
            lastScanned = 0;
            lastPriceLines = 0;
            statusTh = "พบ /worth แต่ไม่พบช่องขาย (GUI " + screenMenuSize
                + " / Player " + playerMenuSize + ")";
            statusEn = "Worth menu detected, but no listing slots (GUI " + screenMenuSize
                + ", player " + playerMenuSize + ")";
            if (++missingSlotsRounds >= 3 && !diagnosticSavedForMenu) {
                diagnosticSavedForMenu = true;
                say(mc, "พบ /worth แต่ไม่พบรายการขาย • บันทึกไฟล์วิเคราะห์แล้ว");
                saveDiagnostic(screen, slots, 0);
            }
            return;
        }
        missingSlotsRounds = 0;

        int added = 0, updated = 0, seen = 0, examined = 0, linesFound = 0;
        String newest = "";
        synchronized (PriceExtractorModule.class) {
            for (int i = 0; i < topSlots; i++) {
                ItemStack stack = slots.get(i).getItem();
                if (stack.isEmpty()) continue;
                seen++;
                List<String> tooltip = itemTooltip(mc, screen, stack);
                examined += tooltip.size();
                PriceTextParser.Prices prices = PriceTextParser.parse(tooltip);
                if (!prices.found()) continue;
                linesFound++;

                String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                String name = stack.getHoverName().getString();
                String key = itemId + "|" + name;
                if (ENTRIES.size() >= MAX_ITEMS && !ENTRIES.containsKey(key)) continue;
                PriceEntry entry = new PriceEntry(itemId, name, prices.perItem(),
                    prices.perStack(), Instant.now().toString());
                PriceEntry previous = ENTRIES.get(key);
                if (previous == null || !previous.price.equals(entry.price)
                    || !previous.perStack.equals(entry.perStack)) {
                    ENTRIES.put(key, entry);
                    if (previous == null) added++;
                    else updated++;
                    newest = name + " = " + entry.price;
                    lastJson = JSON.toJson(entry);
                }
            }
            itemCount = ENTRIES.size();
            if (!newest.isBlank()) lastItem = newest;
        }
        lastScanned = seen;
        lastPriceLines = linesFound;
        statusTh = "กำลังสแกน /worth • พบช่อง " + seen + " • มีราคา " + linesFound
            + " • สะสม " + itemCount;
        statusEn = "Scanning /worth • Items " + seen + " • Priced " + linesFound
            + " • Saved " + itemCount;
        if ((added > 0 || updated > 0) && EasyLifeSystemConfig.get().extractorNotifications) {
            say(mc, "พบใหม่ " + added + " • ราคาเปลี่ยน " + updated + " • สะสม " + itemCount
                + " | " + newest);
        }
        if (seen > 0 && linesFound == 0 && ++emptyRounds >= 5 && !warnedNoPrice) {
            warnedNoPrice = true;
            diagnosticSavedForMenu = true;
            say(mc, "พบ /worth " + seen + " ช่อง แต่ยังไม่พบราคาจาก Tooltip"
                + " • ดูสถานะใน Easylife → สแกนราคา");
            System.err.println("[Easylife] /worth found " + seen
                + " slots and " + examined + " tooltip lines, but no recognized price labels.");
            saveDiagnostic(screen, slots, topSlots);
        } else if (linesFound > 0) {
            emptyRounds = 0;
            lastError = "";
        }
    }

    /** Read the actively hovered slot through an optional runtime field.
     * Uses reflection because 26.3 mapping variants expose different accessor
     * signatures. A failed lookup never prevents tooltip callbacks from running.
     */
    private Slot hoveredSlotFromScreen(AbstractContainerScreen<?> screen) {
        if (!hoveredSlotLookupDone) {
            hoveredSlotLookupDone = true;
            try {
                hoveredSlotField = AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
                hoveredSlotField.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                hoveredSlotField = null;
                System.err.println("[Easylife] hoveredSlot field unavailable: " + e.getMessage());
            }
        }
        if (hoveredSlotField == null) return null;
        try {
            Object slot = hoveredSlotField.get(screen);
            return slot instanceof Slot hovered ? hovered : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * Active hover fallback: the actual slot beneath the cursor can be inspected
     * even when a modded tooltip renderer does not dispatch Fabric tooltip events.
     */
    private void scanHoveredSlot(Minecraft mc, AbstractContainerScreen<?> screen) {
        try {
            Slot hovered = hoveredSlotFromScreen(screen);
            if (hovered == null || hovered.getItem().isEmpty()) return;
            List<Slot> slots = screen.getMenu().slots;
            int index = slots.indexOf(hovered);
            int listingSlots = slots.size() > 36 ? slots.size() - 36 : slots.size();
            if (index < 0 || index >= listingSlots) return;
            List<String> lines = itemTooltip(mc, screen, hovered.getItem());
            if (!lines.isEmpty()) {
                List<Component> text = lines.stream().map(Component::literal)
                    .map(component -> (Component) component).toList();
                observeRenderedTooltip(hovered.getItem(), text);
            }
        } catch (RuntimeException e) {
            if (lastError.isBlank()) lastError = "Hover scanner: " + e.getClass().getSimpleName();
        }
    }

    private List<String> itemTooltip(Minecraft mc, AbstractContainerScreen<?> screen, ItemStack stack) {
        List<String> all = new ArrayList<>();
        // Primary source: actual on-screen tooltip including client-added lines.
        try {
            if (!tooltipLookupDone) {
                tooltipLookupDone = true;
                containerTooltipMethod = AbstractContainerScreen.class.getDeclaredMethod(
                    "getTooltipFromContainerItem", ItemStack.class);
                containerTooltipMethod.setAccessible(true);
            }
            if (containerTooltipMethod != null) {
                Object result = containerTooltipMethod.invoke(screen, stack);
                if (result instanceof List<?> values) {
                    for (Object value : values) {
                        if (value instanceof Component text) all.add(text.getString());
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (lastError.isBlank()) {
                lastError = "Tooltip reflection: " + error.getClass().getSimpleName();
                System.err.println("[Easylife] " + lastError + ": " + error.getMessage());
            }
        }
        // The base vanilla tooltip is a safe fallback when a mod changes the container tooltip.
        try {
            for (Component line : Screen.getTooltipFromItem(mc, stack)) {
                String value = line.getString();
                if (!all.contains(value)) all.add(value);
            }
        } catch (RuntimeException ignored) {
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                String value = line.getString();
                if (!all.contains(value)) all.add(value);
            }
        }
        lastTooltipLines = Math.max(lastTooltipLines, all.size());
        return all;
    }


    private static int filledListingSlots(List<Slot> slots) {
        if (slots == null || slots.size() <= 36) return 0;
        int limit = slots.size() - 36;
        int filled = 0;
        for (int i = 0; i < limit; i++) {
            if (!slots.get(i).getItem().isEmpty()) filled++;
        }
        return filled;
    }

    private static boolean isWorthTitle(String title) {
        if (title == null) return false;
        // Accept ordinary and full-width UI titles without scanning unrelated menus.
        return title.toLowerCase(Locale.ROOT)
            .replace("ｗ", "w").replace("ｏ", "o").replace("ｒ", "r")
            .replace("ｔ", "t").replace("ｈ", "h").contains("worth");
    }

    /** Receives the tooltip Minecraft actually displays to the player at hover time. */
    public static void observeRenderedTooltip(ItemStack stack, List<Component> lines) {
        if (stack == null || stack.isEmpty() || lines == null || lines.isEmpty()) return;
        EasyLifeSystemConfig cfg = EasyLifeSystemConfig.get();
        if (!cfg.priceExtractorEnabled || "auto".equals(cfg.priceScanMode)) return;
        Minecraft mc = Minecraft.getInstance();
        // The older ItemPriceExtractor captured rendered tooltips without
        // requiring an exact /worth title. Keep that behavior: custom shop GUIs
        // may decorate/translate the title, while the price label is definitive.
        if (mc == null || mc.gui == null
            || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
        observedTooltipCalls++;
        lastDetectedTitle = screen.getTitle().getString();
        List<String> raw = new ArrayList<>();
        for (Component line : lines) raw.add(line.getString());
        lastTooltipLines = Math.max(lastTooltipLines, raw.size());
        PriceTextParser.Prices parsed = PriceTextParser.parse(raw);
        if (!parsed.found()) return;
        observedTooltipPrices++;
        synchronized (PriceExtractorModule.class) {
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String name = stack.getHoverName().getString();
            String key = id + "|" + name;
            if (ENTRIES.size() >= MAX_ITEMS && !ENTRIES.containsKey(key)) return;
            PriceEntry newItem = new PriceEntry(id, name,
                parsed.perItem(), parsed.perStack(), Instant.now().toString());
            PriceEntry old = ENTRIES.get(key);
            boolean changedPrice = old == null || !old.price.equals(newItem.price)
                || !old.perStack.equals(newItem.perStack);
            if (changedPrice) {
                ENTRIES.put(key, newItem);
                lastJson = JSON.toJson(newItem);
                lastItem = name + " = " + newItem.price;
                if (cfg.extractorNotifications) {
                    say(mc, "Hover พบ " + name + " • ราคา " + newItem.price
                        + " • รวม " + ENTRIES.size() + " รายการ");
                    PriceToast.show(name + " • ราคา " + newItem.price
                        + " • รวม " + ENTRIES.size() + " รายการ");
                }
            }
            itemCount = ENTRIES.size();
        }
    }

    /**
     * Bounded diagnostics only; records item tooltip lines from the currently
     * opened /worth screen, never the player's name, IP, credentials or chat.
     * Written once per opened menu if scanning cannot recognize any prices.
     */
    private void saveDiagnostic(AbstractContainerScreen<?> screen,
                                List<Slot> slots, int topSlots) {
        try {
            Map<String, Object> debug = new LinkedHashMap<>();
            debug.put("mod", "Easylife");
            debug.put("screenTitle", screen.getTitle().getString());
            debug.put("slotsTotal", slots.size());
            debug.put("guiSlots", screenMenuSize);
            debug.put("playerSlots", playerMenuSize);
            debug.put("source", selectedMenuSource);
            debug.put("topSlots", topSlots);
            debug.put("visibleNonEmpty", lastScanned);
            debug.put("recognizedPriceEntries", lastPriceLines);
            debug.put("hoverCallbackCalls", observedTooltipCalls);
            debug.put("hoverPricedCalls", observedTooltipPrices);
            debug.put("lastError", lastError);
            List<Object> samples = new ArrayList<>();
            for (int i = 0; i < topSlots && samples.size() < 8; i++) {
                ItemStack item = slots.get(i).getItem();
                if (item.isEmpty()) continue;
                Map<String, Object> sample = new LinkedHashMap<>();
                sample.put("slot", i);
                sample.put("itemId", BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
                sample.put("name", item.getHoverName().getString());
                List<String> tooltip = itemTooltip(Minecraft.getInstance(), screen, item);
                sample.put("tooltip", tooltip.subList(0, Math.min(16, tooltip.size())));
                ItemLore lore = item.get(DataComponents.LORE);
                if (lore != null) {
                    List<String> loreText = new ArrayList<>();
                    for (Component component : lore.lines()) loreText.add(component.getString());
                    sample.put("lore", loreText.subList(0, Math.min(16, loreText.size())));
                }
                samples.add(sample);
            }
            debug.put("itemsSampled", samples);
            Path path = FabricLoader.getInstance().getConfigDir()
                .resolve("easylife-price-diagnostic.json");
            Files.writeString(path, JSON.toJson(debug), StandardCharsets.UTF_8);
            lastDiagnosticFile = path.toString();
            say(Minecraft.getInstance(), "อ่านราคาไม่ได้ • บันทึกผลตรวจที่ config/easylife-price-diagnostic.json");
        } catch (Exception exception) {
            System.err.println("[Easylife] Unable to save diagnostic file: " + exception);
        }
    }

    /** SDL key event fallback based on the user's ItemPriceExtractor 1.2.
     * This ensures remapped shortcut keys can work even with a container open.
     */
    public static void onRawKeyPress(int action, KeyEvent event) {
        if (action != 1 || event == null || !TOGGLE.matches(event)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null
            || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
            || !isWorthTitle(screen.getTitle().getString())) return;
        toggleEnabled(mc);
    }

    private static void toggleEnabled(Minecraft mc) {
        // One physical press can reach SDL, ScreenKeyboardEvents and KeyMapping.
        // Debounce the overlapping dispatch paths.
        long now = System.nanoTime();
        if (now - lastShortcutNanos < 150_000_000L) return;
        lastShortcutNanos = now;
        EasyLifeSystemConfig cfg = EasyLifeSystemConfig.get().copy();
        // In /worth, F7 means "start/check" rather than accidentally switching
        // off a scanner already enabled through the Mod Menu.
        boolean insideWorth = mc != null && mc.gui != null
            && mc.gui.screen() instanceof AbstractContainerScreen<?> screen
            && isWorthTitle(screen.getTitle().getString());
        if (insideWorth) {
            if (cfg.priceExtractorEnabled) {
                say(mc, "Price Scan เปิดอยู่แล้ว • รายการ " + itemCount
                    + " • " + getDiagnostics());
            } else {
                cfg.priceExtractorEnabled = true;
                EasyLifeSystemConfig.save(cfg);
                say(mc, "เปิด Price Scan แล้ว • กำลังอ่าน /worth");
            }
        } else {
            cfg.priceExtractorEnabled = !cfg.priceExtractorEnabled;
            EasyLifeSystemConfig.save(cfg);
            say(mc, cfg.priceExtractorEnabled
                ? "Price Scan เปิดแล้ว • พิมพ์ /worth"
                : "ปิด Price Scan แล้ว");
        }
    }

    private static void say(Minecraft mc, String line) {
        if (mc != null && mc.player != null)
            mc.player.sendSystemMessage(Component.literal("[Easylife] " + line));
    }

    private synchronized void ensureServer() {
        int port = EasyLifeSystemConfig.get().extractorPort;
        if (server != null && serverPort == port) return;
        stopServer();
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            http.createContext("/logo.png", ex -> serveLogo(ex));
            http.createContext("/", ex -> serve(ex, loadWebsite(), "text/html; charset=utf-8"));
            http.createContext("/items", ex -> serve(ex, itemsJson(), "application/json; charset=utf-8"));
            http.createContext("/data", ex -> serve(ex, lastJson, "application/json; charset=utf-8"));
            http.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "easylife-worth-web");
                t.setDaemon(true);
                return t;
            }));
            http.start();
            server = http;
            serverPort = port;
            lastError = "";
        } catch (IOException e) {
            String message = "เปิดเว็บพอร์ต " + port + " ไม่ได้";
            if (!message.equals(lastError)) System.err.println("[Easylife] " + message + ": " + e);
            lastError = message;
            statusTh = message;
            statusEn = "Cannot open local port " + port;
        }
    }

    private synchronized void stopServer() {
        if (server != null) server.stop(0);
        server = null;
        serverPort = -1;
    }

    private static String itemsJson() {
        synchronized (PriceExtractorModule.class) {
            return JSON.toJson(new ArrayList<>(ENTRIES.values()));
        }
    }

    private static String loadWebsite() {
        try (InputStream input = PriceExtractorModule.class.getResourceAsStream(
            "/assets/scautoauction/web/index.html")) {
            if (input == null) return "<h1>Missing Price Extractor page</h1>";
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<h1>Unable to open Price Extractor page</h1>";
        }
    }

    /** Serve Siamcraft's bundled logo locally: the dashboard needs no external logo host. */
    private static void serveLogo(HttpExchange ex) throws IOException {
        try (InputStream in = PriceExtractorModule.class.getResourceAsStream(
                "/assets/scautoauction/textures/gui/siamcraft_logo.png")) {
            if (in == null) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            byte[] image = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "image/png");
            ex.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.sendResponseHeaders(200, image.length);
            try (OutputStream out = ex.getResponseBody()) { out.write(image); }
        }
    }

    private static void serve(HttpExchange ex, String content, String mimeType) throws IOException {
        ex.getResponseHeaders().set("Content-Type", mimeType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private static final class PriceEntry {
        final String itemId;
        final String name;
        final String perItem;
        final String perStack;
        final String price;
        final String updatedAt;

        PriceEntry(String itemId, String name, String perItem, String perStack, String updatedAt) {
            this.itemId = itemId;
            this.name = name;
            this.perItem = perItem;
            this.perStack = perStack;
            this.price = !perItem.isEmpty() ? perItem : perStack;
            this.updatedAt = updatedAt;
        }
    }
}
