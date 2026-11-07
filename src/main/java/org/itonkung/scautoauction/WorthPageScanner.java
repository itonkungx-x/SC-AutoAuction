package org.itonkung.scautoauction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-closed navigator for Siamcraft's observed /worth 6-row chest UI.
 * Item region: columns 2..7, rows 1..4. Next page: slot (5,7) = 52.
 *
 * Sends a single normal left click on a verified arrow every page.
 * Never interacts with the player's inventory, any other GUI or an unknown
 * menu layout; waits for a title page change before another click.
 */
public final class WorthPageScanner {
    private static final Pattern PAGE_TITLE = Pattern.compile(
        "(?i)worth\\s*[(（]\\s*(\\d{1,4})\\s*/\\s*(\\d{1,4})\\s*[)）]");
    private static final int NEXT_SLOT = 5 * 9 + 7;
    private int page = -1, total = -1, elapsedInPage, waitingForPage = -1, pendingTicks;
    private boolean stopped, notified;
    private String status = "รอเปิด /worth";

    public static boolean itemSlot(int slot) {
        int row = slot / 9, col = slot % 9;
        return row >= 1 && row <= 4 && col >= 2 && col <= 7;
    }

    public String getStatus() { return status; }

    public void reset() {
        page = -1;
        total = -1;
        elapsedInPage = 0;
        waitingForPage = -1;
        pendingTicks = 0;
        stopped = false;
        notified = false;
        status = "รอเปิด /worth";
    }

    public void tick(Minecraft mc, AbstractContainerScreen<?> screen, int scanned, int priced) {
        if (!EasyLifeSystemConfig.get().autoScanWorthPages) {
            if (page != -1) reset();
            return;
        }
        if (stopped || mc == null || mc.player == null || mc.gameMode == null) return;
        Matcher match = PAGE_TITLE.matcher(screen.getTitle().getString());
        if (!match.find()) {
            status = "หยุด: ชื่อหน้า /worth ไม่ตรงรูปแบบ";
            stopped = true; return;
        }
        int current = Integer.parseInt(match.group(1)), maximum = Integer.parseInt(match.group(2));
        if (maximum < 1 || maximum > 500 || current < 1 || current > maximum) {
            stop(mc,"หยุด: หมายเลขหน้าผิดปกติ"); return;
        }
        if (screen.getMenu().slots.size() < 54) {
            stop(mc,"หยุด: GUI ไม่ใช่ 6 แถว"); return;
        }
        if (waitingForPage > 0) {
            if (current == waitingForPage) {
                waitingForPage = -1;
                pendingTicks = 0;
            } else {
                if (++pendingTicks > 7) stop(mc,"หยุด: เซิร์ฟเวอร์ยังไม่เปลี่ยนหน้า");
                return; // Never send the same click twice.
            }
        }
        if (current != page || maximum != total) {
            page = current; total = maximum; elapsedInPage = 0;
            status = "กำลังสแกนหน้า " + page + "/" + total;
            if (!notified) {
                notified = true;
                mc.player.sendSystemMessage(Component.literal(
                    "[Easylife] สแกน /worth ทุกหน้า เริ่มจาก " + page + "/" + total));
            }
        }
        if (current == maximum) {
            stop(mc,"ครบแล้ว " + total + " หน้า"); return;
        }
        if (++elapsedInPage < 2) return;  // At least 24 ticks after reading the page.
        if (priced == 0 || scanned == 0) {
            if (elapsedInPage > 9) stop(mc,"หยุด: หน้านี้ยังอ่านราคาไม่ได้");
            return;
        }
        // Prevent carrying, swapping or moving actual items.
        if (!screen.getMenu().getCarried().isEmpty()) {
            stop(mc,"หยุด: มีของติดเคอร์เซอร์อยู่"); return;
        }
        List<Slot> slots = screen.getMenu().slots;
        if (NEXT_SLOT >= slots.size()) {
            stop(mc,"หยุด: ไม่มีช่องหน้าถัดไป"); return;
        }
        ItemStack button = slots.get(NEXT_SLOT).getItem();
        String itemId = button.isEmpty() ? "" :
            BuiltInRegistries.ITEM.getKey(button.getItem()).toString();
        if (!"minecraft:arrow".equals(itemId)) {
            stop(mc,"หยุด: ช่องหน้าถัดไปไม่ใช่ลูกศร"); return;
        }
        mc.gameMode.handleContainerInput(screen.getMenu().containerId,
            NEXT_SLOT, 0, ContainerInput.PICKUP, mc.player);
        waitingForPage = page + 1;
        pendingTicks = 0;
        status = "เปลี่ยนหน้า " + page + " → " + waitingForPage;
    }

    private void stop(Minecraft mc, String message) {
        status = message; stopped = true;
        if (mc.player != null)
            mc.player.sendSystemMessage(Component.literal("[Easylife] " + message));
    }
}
