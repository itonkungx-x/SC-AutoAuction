package org.itonkung.scautoauction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real cursor-hover scanner for the Siamcraft /worth 6-row GUI.
 * Product positions: 6 columns x 4 rows. Next button: slot 52 (0-based).
 * One cursor movement per product, then wait for the Minecraft tooltip renderer.
 */
public final class WorthPageScanner {
    private static final Pattern PAGE = Pattern.compile(
        "(?i)worth\\s*[(（]\\s*(\\d{1,4})\\s*/\\s*(\\d{1,4})\\s*[)）]");
    private static final int NEXT = 52;
    private static final int HOVER_WAIT = 2;          // 2 cycles of 4 ticks
    private static final int HOVER_MAX_WAIT = 6;
    private static final int PAGE_MAX_WAIT = 30;
    private int current = -1, total = -1, waitingFor = -1, wait = 0;
    private int cursor = 0, dwell = 0, priced = 0, missing = 0;
    private boolean stopped = false, announced = false, savedPointer = false;
    private double originalX, originalY;
    private String status = "รอเปิด /worth";
    private final List<Integer> targets = new ArrayList<>();

    public static boolean itemSlot(int index) {
        int row = index / 9, column = index % 9;
        return row >= 1 && row <= 4 && column >= 2 && column <= 7;
    }
    public String getStatus() { return status; }
    public void reset() { reset(Minecraft.getInstance()); }
    public void reset(Minecraft mc) {
        restorePointer(mc);
        current=-1; total=-1; waitingFor=-1; wait=0;
        cursor=0; dwell=0; priced=0; missing=0;
        stopped=false; announced=false; targets.clear();
        status="รอเปิด /worth";
    }

    public void tick(Minecraft mc, AbstractContainerScreen<?> screen,
                     Predicate<ItemStack> savedPrice) {
        if (!EasyLifeSystemConfig.get().autoScanWorthPages) {
            if (current!=-1 || savedPointer) reset(mc);
            return;
        }
        if (stopped || mc==null || mc.player==null || mc.gameMode==null) return;
        Matcher m=PAGE.matcher(screen.getTitle().getString());
        if (!m.find()) { stop(mc,"หยุด: ไม่พบชื่อ WORTH (หน้า/ทั้งหมด)"); return; }
        int page=Integer.parseInt(m.group(1)), pages=Integer.parseInt(m.group(2));
        if (pages<1 || pages>500 || page<1 || page>pages) {
            stop(mc,"หยุด: หมายเลขหน้าไม่ถูกต้อง"); return;
        }
        List<Slot> all=screen.getMenu().slots;
        if (all.size()<90) { stop(mc,"หยุด: /worth ไม่มีช่องครบ 6 แถว"); return; }
        if (!screen.getMenu().getCarried().isEmpty()) {
            stop(mc,"หยุด: ไอเท็มติดเคอร์เซอร์อยู่"); return;
        }
        if (waitingFor>0) {
            if (page!=waitingFor) {
                if (++wait>PAGE_MAX_WAIT) stop(mc,"หยุด: เซิร์ฟเวอร์ไม่เปลี่ยนหน้า");
                return;
            }
            waitingFor=-1; wait=0;
        }
        if (page!=current || pages!=total) {
            current=page; total=pages; cursor=0; dwell=0; priced=0; missing=0;
            targets.clear();
            for (int i=0;i<54;i++) {
                if (itemSlot(i) && !all.get(i).getItem().isEmpty()) targets.add(i);
            }
            if (targets.isEmpty()) { stop(mc,"หยุด: ไม่พบสินค้าในกรอบ"); return; }
            if (!announced) {
                announced=true;
                say(mc,"เริ่ม Auto Hover " + pages + " หน้า • กด F7 เพื่อหยุด");
            }
            status="หน้า "+page+"/"+pages+" • เริ่ม "+targets.size()+" ช่อง";
        }

        if (cursor<targets.size()) {
            Slot slot=all.get(targets.get(cursor));
            if (dwell==0) {
                if (!movePointer(mc,screen,slot)) {
                    stop(mc,"หยุด: ขยับเมาส์ไปช่องไอเท็มไม่สำเร็จ"); return;
                }
                dwell=1;
                status="หน้า "+page+"/"+pages+" • Hover "+(cursor+1)+"/"+targets.size()
                    +" • พบราคา "+priced;
                return;
            }
            dwell++;
            boolean found=savedPrice.test(slot.getItem());
            if (dwell<HOVER_WAIT || (!found && dwell<HOVER_MAX_WAIT)) return;
            if (found) priced++; else missing++;
            cursor++; dwell=0;
            return;
        }

        if (missing>0 || priced==0) {
            stop(mc,"หยุดหน้า "+page+": ราคาไม่ครบ "+priced+"/"+targets.size()
                +" • ส่งไฟล์ easylife-price-diagnostic.json เพื่อแก้");
            return;
        }
        if (page==pages) {
            stop(mc,"สแกนครบ "+pages+" หน้า • รายการถูกบันทึกแล้ว");
            return;
        }
        ItemStack arrow=all.get(NEXT).getItem();
        String id=arrow.isEmpty()?"":BuiltInRegistries.ITEM.getKey(arrow.getItem()).toString();
        if (!"minecraft:arrow".equals(id)) {
            stop(mc,"หยุด: ช่อง 52 ไม่ใช่ลูกศรหน้าถัดไป ("+id+")");
            return;
        }
        mc.gameMode.handleContainerInput(screen.getMenu().containerId,
            NEXT, 0, ContainerInput.PICKUP, mc.player);
        waitingFor=page+1; wait=0;
        status="หน้า "+page+"/"+pages+" • กำลังเปลี่ยนเป็นหน้า "+waitingFor;
    }

    private boolean movePointer(Minecraft mc, AbstractContainerScreen<?> screen, Slot slot) {
        try {
            Field left=AbstractContainerScreen.class.getDeclaredField("leftPos");
            Field top=AbstractContainerScreen.class.getDeclaredField("topPos");
            left.setAccessible(true);
            top.setAccessible(true);
            double guiX=left.getInt(screen)+slot.x+8;
            double guiY=top.getInt(screen)+slot.y+8;
            long hwnd=mc.getWindow().getWindow();
            if (!savedPointer) {
                DoubleBuffer x=BufferUtils.createDoubleBuffer(1);
                DoubleBuffer y=BufferUtils.createDoubleBuffer(1);
                GLFW.glfwGetCursorPos(hwnd,x,y);
                originalX=x.get(0); originalY=y.get(0); savedPointer=true;
            }
            double px=guiX*mc.getWindow().getWidth() / Math.max(1,mc.getWindow().getGuiScaledWidth());
            double py=guiY*mc.getWindow().getHeight() / Math.max(1,mc.getWindow().getGuiScaledHeight());
            GLFW.glfwSetCursorPos(hwnd,px,py);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            System.err.println("[Easylife] Auto Hover: "+ex.getClass().getSimpleName()+": "+ex.getMessage());
            return false;
        }
    }
    private void restorePointer(Minecraft mc) {
        if (!savedPointer) return;
        savedPointer=false;
        if (mc==null) return;
        try { GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),originalX,originalY); }
        catch (RuntimeException ignored) {}
    }
    private void stop(Minecraft mc,String message) {
        if (stopped) return;
        stopped=true;status=message;restorePointer(mc);say(mc,message);
    }
    private void say(Minecraft mc,String message) {
        if (mc!=null && mc.player!=null)
            mc.player.sendSystemMessage(Component.literal("[Easylife] "+message));
    }
}
