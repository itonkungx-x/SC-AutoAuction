package org.itonkung.scautoauction;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Easylife settings UI with ChatTools-style tabs, search and fixed Save & Quit.
 * Language is independent of the Minecraft language; Thai is the default.
 */
public final class EasyLifeSettingsScreen extends Screen {
    private enum Page { GENERAL, AUCTION, PRICE, HOTKEYS, UPDATE }
    private static final Identifier LOGO = Identifier.fromNamespaceAndPath(
        "scautoauction", "textures/gui/siamcraft_logo.png");
    private static final String[] TABS = {
        "ui.tab.general", "ui.tab.auction", "ui.tab.price", "ui.tab.hotkeys", "ui.tab.update"
    };
    private static final String[] AUCTION_FIELDS = {
        "easylife.setting.starting_price", "easylife.setting.increment",
        "easylife.setting.buyout", "easylife.setting.duration",
        "easylife.setting.overtime", "easylife.setting.start_time"
    };
    private static final int GOLD = 0xFFFFC36F;
    private static final int TEXT = 0xFFE7E7E7;
    private static final int MUTED = 0xFFABACB5;

    private record Row(String labelKey, String hintKey, AbstractWidget widget,
                       Supplier<String> dynamicValue) { }

    private final Screen parent;
    private final Page initialPage;
    private EasyLifeConfig auction;
    private EasyLifeSystemConfig systems;
    private Page page;
    private final List<Row> rows = new ArrayList<>();
    private final EditBox[] auctionFields = new EditBox[6];
    private EditBox delayBox;
    private EditBox portBox;
    private EditBox search;
    private String query = "";
    private String error = "";
    private int left, right, controlX, controlWidth, topY, rowHeight;
    private int contentX;
    private int searchY;

    public EasyLifeSettingsScreen(Screen parent) {
        this(parent, Page.GENERAL, "");
    }

    private EasyLifeSettingsScreen(Screen parent, Page initialPage, String searchQuery) {
        super(Component.literal("Easylife"));
        this.parent = parent;
        this.initialPage = initialPage;
        this.page = initialPage;
        this.query = searchQuery;
        this.auction = EasyLifeConfig.get().copy();
        this.systems = EasyLifeSystemConfig.get().copy();
    }

    @Override
    protected void init() {
        super.init();
        rows.clear();
        left = 22;
        right = width - 22;
        controlWidth = Math.min(235, Math.max(135, (right - left) / 3));
        controlX = right - controlWidth - 8;
        contentX = left + 22;
        searchY = 65;
        topY = 122;
        rowHeight = Math.max(25, Math.min(39, (height - topY - 65) / 9));
        error = "";

        int tabsX = 145;
        int tabSpace = Math.max(0, right - tabsX);
        int tabWidth = Math.max(64, (tabSpace - 20) / TABS.length);
        for (int i = 0; i < TABS.length; i++) {
            Page target = Page.values()[i];
            int x = tabsX + i * tabWidth;
            addRenderableWidget(Button.builder(EasyLifeText.of(TABS[i]), b -> selectTab(target))
                .bounds(x, 13, tabWidth - 4, 22).build());
        }

        int searchWidth = Math.min(500, Math.max(170, width - 225));
        int searchX = (width - searchWidth) / 2;
        search = new EditBox(font, searchX, searchY, searchWidth, 20,
            EasyLifeText.of("ui.search"));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            filterRows();
        });
        addRenderableWidget(search);

        switch (page) {
            case GENERAL -> generalPage();
            case AUCTION -> auctionPage();
            case PRICE -> pricePage();
            case HOTKEYS -> hotkeyPage();
            case UPDATE -> updatePage();
        }

        int buttonWidth = Math.min(280, (width - 64) / 2);
        addRenderableWidget(Button.builder(EasyLifeText.of("ui.cancel"), b -> onClose())
            .bounds(width / 2 - buttonWidth - 5, height - 36, buttonWidth, 25).build());
        addRenderableWidget(Button.builder(EasyLifeText.of("ui.save"), b -> saveAndClose())
            .bounds(width / 2 + 5, height - 36, buttonWidth, 25).build());
        filterRows();
    }

    private void generalPage() {
        addButtonRow("ui.setting.auction", "ui.info.auction",
            systems.auctionEnabled ? "ui.enabled" : "ui.disabled", () -> {
                systems.auctionEnabled = !systems.auctionEnabled;
                selectTab(Page.GENERAL);
            });
        addButtonRow("ui.setting.price", "ui.info.price",
            systems.priceExtractorEnabled ? "ui.enabled" : "ui.disabled", () -> {
                systems.priceExtractorEnabled = !systems.priceExtractorEnabled;
                selectTab(Page.GENERAL);
            });
        addButtonRow("ui.setting.language", "ui.info.language",
            "en_us".equals(systems.uiLanguage) ? "ui.lang.en" : "ui.lang.th", () -> {
                systems.uiLanguage = "th_th".equals(systems.uiLanguage) ? "en_us" : "th_th";
                // Changing UI language immediately is intentional: it does not change Minecraft's language.
                EasyLifeSystemConfig.save(systems);
                selectTab(Page.GENERAL);
            });
        addTextRow("ui.setting.modules", "ui.info.modules",
            () -> (systems.auctionEnabled ? "Auction " : "") +
                (systems.priceExtractorEnabled ? "Price Extractor" : "") +
                (!systems.auctionEnabled && !systems.priceExtractorEnabled ? "OFF" : ""));
        addTextRow("ui.setting.scanned", "ui.info.scanned",
            () -> Integer.toString(PriceExtractorModule.getFoundCount()));
        addButtonRow("ui.setting.keybinds", "ui.info.keybinds",
            "ui.open_keys", this::openKeyBinds);
    }

    private void auctionPage() {
        String[] values = auction.formValues();
        for (int i = 0; i < AUCTION_FIELDS.length; i++) {
            EditBox box = new EditBox(font, controlX, topY + i * rowHeight,
                controlWidth, 20, EasyLifeText.of(AUCTION_FIELDS[i]));
            box.setMaxLength(i == 5 ? 32 : 18);
            box.setValue(values[i]);
            auctionFields[i] = box;
            addRow(AUCTION_FIELDS[i], "ui.info.edit", box, null);
        }
        delayBox = new EditBox(font, controlX, topY + 6 * rowHeight,
            controlWidth, 20, EasyLifeText.of("easylife.setting.delay"));
        delayBox.setMaxLength(3);
        delayBox.setValue(Integer.toString(auction.betweenListingsTicks));
        addRow("easylife.setting.delay", "ui.info.delay", delayBox, null);
        addButtonRow("ui.setting.keybinds", "ui.info.keybinds",
            "ui.open_keys", this::openKeyBinds);
    }

    private void pricePage() {
        addButtonRow("ui.setting.price", "ui.info.price",
            systems.priceExtractorEnabled ? "ui.enabled" : "ui.disabled", () -> {
                systems.priceExtractorEnabled = !systems.priceExtractorEnabled;
                selectTab(Page.PRICE);
            });
        addButtonRow("ui.setting.scanmode", "ui.info.scanmode",
            switch (systems.priceScanMode) {
                case "auto" -> "ui.scanmode.auto";
                case "hover" -> "ui.scanmode.hover";
                default -> "ui.scanmode.both";
            }, () -> {
                systems.priceScanMode = switch (systems.priceScanMode) {
                    case "auto" -> "hover";
                    case "hover" -> "both";
                    default -> "auto";
                };
                selectTab(Page.PRICE);
            });
        addButtonRow("ui.setting.autopages", "ui.info.autopages",
            systems.autoScanWorthPages ? "ui.enabled" : "ui.disabled", () -> {
                systems.autoScanWorthPages = !systems.autoScanWorthPages;
                selectTab(Page.PRICE);
            });
        addButtonRow("ui.setting.notifications", "ui.info.notifications",
            systems.extractorNotifications ? "ui.enabled" : "ui.disabled", () -> {
                systems.extractorNotifications = !systems.extractorNotifications;
                selectTab(Page.PRICE);
            });
        portBox = new EditBox(font, controlX, topY + 4 * rowHeight,
            controlWidth, 20, EasyLifeText.of("easylife.extractor.port"));
        portBox.setMaxLength(5);
        portBox.setValue(Integer.toString(systems.extractorPort));
        addRow("easylife.extractor.port", "ui.info.port", portBox, null);
        addTextRow("ui.setting.scanned", "ui.info.scanned",
            () -> Integer.toString(PriceExtractorModule.getFoundCount()));
        addTextRow("ui.setting.status", "ui.info.status",
            () -> PriceExtractorModule.getStatusForLanguage(systems.uiLanguage));
        addTextRow("ui.setting.diagnostics", "ui.info.diagnostics",
            PriceExtractorModule::getDiagnostics);
        addButtonRow("ui.setting.website", "ui.info.website",
            "ui.open_website", this::openWebsite);
        addButtonRow("ui.setting.clear", "ui.info.clear",
            "ui.clear_now", PriceExtractorModule::clearItems);
    }

    private void hotkeyPage() {
        addTextRow("ui.setting.auction_key", "ui.info.auction_key", () -> "F8");
        addTextRow("ui.setting.stop_key", "ui.info.stop_key", () -> "F9");
        addTextRow("ui.setting.extractor_key", "ui.info.extractor_key", () -> "F7");
        addButtonRow("ui.setting.keybinds", "ui.info.keybinds",
            "ui.open_keys", this::openKeyBinds);
        addTextRow("ui.setting.keyboard", "ui.info.keyboard",
            () -> EasyLifeText.get("ui.keyboard_note"));
    }

    private void updatePage() {
        addButtonRow("ui.setting.autoupdate", "ui.info.autoupdate",
            auction.autoUpdate ? "ui.enabled" : "ui.disabled", () -> {
                auction.autoUpdate = !auction.autoUpdate;
                selectTab(Page.UPDATE);
            });
        addButtonRow("ui.setting.check", "ui.info.check",
            "ui.check_now", () -> EasyLifeUpdater.checkForUpdates(true));
        addTextRow("ui.setting.version", "ui.info.version",
            () -> FabricLoader.getInstance().getModContainer("scautoauction")
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?"));
        addTextRow("ui.setting.update_status", "ui.info.update_status",
            () -> EasyLifeUpdater.getStatusText().getString());
    }

    private void addButtonRow(String key, String info, String buttonKey, Runnable onPress) {
        Button button = Button.builder(EasyLifeText.of(buttonKey), b -> onPress.run())
            .bounds(controlX, 0, controlWidth, 22).build();
        addRow(key, info, button, null);
    }

    private void addTextRow(String key, String info, Supplier<String> dynamicValue) {
        addRow(key, info, null, dynamicValue);
    }

    private void addRow(String key, String info, AbstractWidget widget, Supplier<String> dynamic) {
        rows.add(new Row(key, info, widget, dynamic));
        if (widget != null) addRenderableWidget(widget);
    }

    private void filterRows() {
        if (rows.isEmpty()) return;
        String term = query.trim().toLowerCase(Locale.ROOT);
        int visible = 0;
        for (Row row : rows) {
            String label = EasyLifeText.getIn("th_th", row.labelKey) + " "
                + EasyLifeText.getIn("en_us", row.labelKey) + " "
                + EasyLifeText.getIn("th_th", row.hintKey) + " "
                + EasyLifeText.getIn("en_us", row.hintKey);
            boolean show = term.isEmpty() || label.toLowerCase(Locale.ROOT).contains(term);
            if (row.widget != null) {
                row.widget.visible = show;
                if (show) row.widget.setY(topY + visible * rowHeight + 6);
            }
            if (show) visible++;
        }
    }

    private void selectTab(Page target) {
        stashCurrent();
        this.page = target;
        // Recreating only the GUI keeps the tabbed controls and state consistent.
        this.minecraft.gui.setScreen(new EasyLifeSettingsScreen(parent, target, query,
            auction, systems));
    }

    private EasyLifeSettingsScreen(Screen parent, Page page, String query,
                                   EasyLifeConfig auction, EasyLifeSystemConfig systems) {
        this(parent, page, query);
        this.auction = auction;
        this.systems = systems;
    }

    private void stashCurrent() {
        if (page == Page.AUCTION && auctionFields[0] != null) {
            String[] values = new String[6];
            for (int i = 0; i < 6; i++) values[i] = auctionFields[i].getValue().trim();
            auction.startingPrice = values[0];
            auction.minimumIncrement = values[1];
            auction.buyoutPrice = values[2];
            auction.durationSeconds = values[3];
            auction.overtimeSeconds = values[4];
            auction.startTime = values[5];
            if (delayBox != null) {
                try { auction.betweenListingsTicks = Integer.parseInt(delayBox.getValue().trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        if (page == Page.PRICE && portBox != null) {
            try { systems.extractorPort = Integer.parseInt(portBox.getValue().trim()); }
            catch (NumberFormatException ignored) { }
        }
    }

    private void saveAndClose() {
        try {
            stashCurrent();
            EasyLifeConfig.save(auction);
            EasyLifeSystemConfig.save(systems);
            if (auction.autoUpdate) EasyLifeUpdater.checkForUpdates(false);
            onClose();
        } catch (RuntimeException e) {
            error = EasyLifeText.get("ui.invalid", e.getMessage());
        }
    }

    private void openKeyBinds() {
        stashCurrent();
        minecraft.gui.setScreen(new KeyBindsScreen(this, minecraft.options));
    }

    private void openWebsite() {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(
                URI.create("http://127.0.0.1:" + systems.extractorPort));
        } catch (Exception e) {
            error = EasyLifeText.get("ui.web_failed");
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        // Header: only the Siamcraft logo. Tabs are separate navigation controls.
        g.fill(0, 0, width, 53, 0xE8111117);
        g.fill(0, 53, width, 54, 0x99FFB24B);
        g.blit(LOGO, 12, 4, 121, 53, 0.0F, 1.0F, 0.0F, 1.0F);
        g.fill(left, 108, right, height - 45, 0xA91C1C21);
        g.fill(0, height - 46, width, height, 0xEA1A1B20);
        super.extractRenderState(g, mx, my, delta);
        int tabSpace = Math.max(0, right - 145);
        int tabWidth = Math.max(64, (tabSpace - 20) / TABS.length);
        int selectedX = 145 + page.ordinal() * tabWidth;
        g.outline(selectedX - 1, 12, tabWidth - 2, 24, 0xFFFFBF61);


        g.text(font, EasyLifeText.of("ui.search"), left + 6, searchY + 5, MUTED);
        g.text(font, EasyLifeText.of(TABS[page.ordinal()]), left + 12, topY - 22, GOLD);

        String term = query.trim().toLowerCase(Locale.ROOT);
        int visible = 0;
        for (Row row : rows) {
            String text = EasyLifeText.getIn("th_th", row.labelKey) + " "
                + EasyLifeText.getIn("en_us", row.labelKey) + " "
                + EasyLifeText.getIn("th_th", row.hintKey) + " "
                + EasyLifeText.getIn("en_us", row.hintKey);
            if (!term.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(term)) continue;
            int y = topY + visible * rowHeight;
            if (visible % 2 == 1) g.fill(left + 2, y, right - 2, y + rowHeight,
                0x29111116);
            g.text(font, EasyLifeText.of(row.labelKey), contentX, y + 4, TEXT);
            if (rowHeight >= 34 && !row.hintKey.isBlank())
                g.text(font, EasyLifeText.of(row.hintKey), contentX + 4, y + 18, MUTED);
            if (row.dynamicValue != null) {
                String value = row.dynamicValue.get();
                if (value.length() > 62) value = value.substring(0, 59) + "...";
                g.text(font, Component.literal(value), controlX + 6, y + 8, GOLD);
            }
            visible++;
        }
        if (visible == 0) g.centeredText(font, EasyLifeText.of("ui.noresults"), width / 2,
            topY + 40, MUTED);
        if (!error.isBlank()) g.centeredText(font, Component.literal(error),
            width / 2, height - 56, 0xFFFF7A7A);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
