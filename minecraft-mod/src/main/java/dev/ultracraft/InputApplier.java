package dev.ultracraft;

import dev.ultracraft.bridge.Bridge;
import dev.ultracraft.bridge.Protocol;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies ULTRAKILL's forwarded keyboard and mouse to Minecraft.
 *
 * <p>Minecraft is not the focused window, so it receives no real input. ULTRAKILL samples
 * its own input and publishes it on the bridge; this applies it to Minecraft's key
 * bindings.
 *
 * <p>Held keys are set directly on the {@link KeyMapping} rather than being replayed as
 * synthetic key events. Minecraft's {@code keyPress(window, key, KeyEvent)} carries no
 * press/release flag, so a replayed event cannot reliably say whether a key went down or
 * up, and held keys would stick. Setting state directly is unambiguous.
 *
 * <p>Mouse movement is forwarded as a delta instead of letting Minecraft poll the cursor.
 * That is what allows Minecraft to turn around without grabbing the OS cursor, which
 * would otherwise steal control from ULTRAKILL. See {@link MouseHandlerMixin} and
 * {@link WindowMixin} for the two halves of that arrangement.
 */
final class InputApplier {

    private static final Logger LOG = LoggerFactory.getLogger(UltraCraftClient.MOD_ID);

    /** GLFW/LWJGL mouse button codes, which are also what Minecraft's own handlers use. */
    // Minecraft 26.x input is SDL3, not GLFW: mouse buttons are SDL's (InputConstants: LEFT = 1,
    // MIDDLE = 2, RIGHT = 3), not GLFW's 0 = left, 1 = right (and keys are SDL scancodes). Hard-coding 0/1 made a forwarded LEFT press
    // an unbound button 0 (no click; only the held state broke blocks, after 3-4 presses) and
    // a forwarded RIGHT press a LEFT click (break-then-replace), measured 2026-10-13:
    // "RMB=true ... clickCounter attack=1". Use Minecraft's own constants.
    private static final int MOUSE_LEFT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
    private static final int MOUSE_RIGHT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;

    private final Bridge bridge;
    private long lastApplied = -1;
    private int applied;
    private long lastMouseMs;

    private boolean wasAttack;
    private boolean wasUse;
    private boolean inventoryHeldForOneSample;

    InputApplier(Bridge bridge) {
        this.bridge = bridge;
    }

    void apply(Minecraft mc) {
        if (mc.player == null) return;

        Bridge.InputState in = readInput();
        if (in == null) return;

        // Instrumentation. "Keys do nothing" has two very different causes: the keys
        // never arriving from ULTRAKILL, or arriving and then being cleared by
        // Minecraft. Reporting both the received bits and the resulting isDown() state
        // distinguishes them in a single playtest instead of by guesswork.
        //
        // DELIBERATELY NOT called here. It reports isDown(), which is state this method
        // sets a few lines below, so running it first reports the previous tick's values
        // and makes every key look permanently dead. It used to be called here, and it is
        // why logs confidently showed "keyAttack.isDown()=false" on samples where the
        // attack button had just arrived as true. The diagnostic was the thing lying.
        reportFirstSample(in);

        long now = System.currentTimeMillis();

        // While a Minecraft screen is open (inventory, chat, pause) keys go to the screen as
        // events (applyEvents), and held movement keys are released, as in vanilla.
        boolean screenOpen = mc.gui.screen() != null;
        int keys = screenOpen ? 0 : in.keys;
        set(mc.options.keyUp,    (keys & Protocol.KEY_FORWARD) != 0);
        set(mc.options.keyDown,  (keys & Protocol.KEY_BACK) != 0);
        set(mc.options.keyLeft,  (keys & Protocol.KEY_LEFT) != 0);
        set(mc.options.keyRight, (keys & Protocol.KEY_RIGHT) != 0);
        set(mc.options.keyJump,  (keys & Protocol.KEY_JUMP) != 0);
        // Minecraft 26.3 has no keySneak; keyShift is the sneak binding.
        set(mc.options.keyShift, (keys & Protocol.KEY_SNEAK) != 0);
        set(mc.options.keySprint, (keys & Protocol.KEY_SPRINT) != 0);

        // Mouse buttons need TWO mechanisms, and only using the obvious one is why
        // left and right click did nothing.
        //
        // 1. Held state. Mining needs keyAttack.isDown() to stay true across ticks, and
        //    startUseItem is reached through a keyUse.isDown() branch. setDown covers
        //    both.
        //
        // 2. A click. Discrete actions are gated on KeyMapping.consumeClick(), which
        //    reads and clears a private clickCount. Nothing but KeyMapping.click()
        //    increments it, and setDown() does not. So startAttack() (the press that
        //    begins mining) never fired either.
        //
        // Rather than poke at clickCount, the press is replayed through Minecraft's own
        // input entry point, MouseHandler.onButton(window, info, action). Verified from
        // the 26.3 bytecode: given Minecraft's real window handle (0 is rejected, see
        // forwardButton), it calls KeyMapping.click(), which increments clickCount
        // for every mapping bound to that physical button, and therefore respects the
        // player's own keybinds instead of assuming left click is attack.
        //
        // Action 1 is press and 0 is release, taken from the bytecode
        // ("iload 4; iconst_1; if_icmpne" guarding activeButton).
        if (in.attack != wasAttack) {
            forwardButton(mc, MOUSE_LEFT, in.attack);
        }
        if (in.use != wasUse) {
            forwardButton(mc, MOUSE_RIGHT, in.use);
        }
        wasAttack = in.attack;
        wasUse = in.use;

        applyHotbarAndScroll(mc, in);
        applyCursor(mc, in);
        applyEvents(mc);

        set(mc.options.keyAttack, in.attack);
        set(mc.options.keyUse, in.use);

        // Now that everything above has been applied, the resulting state is worth
        // reporting. See the note at the call site for why this cannot come earlier.
        reportApplied(mc, in);

        // The inventory key is a keyboard press, not a mouse button, so there is no
        // physical button to replay. Minecraft consumes it through the same click
        // counter, so it is held for exactly one applied sample and released on the
        // next, which gives it a press without leaving it stuck down.
        if (in.inventory) {
            set(mc.options.keyInventory, true);
            inventoryHeldForOneSample = true;
        } else if (inventoryHeldForOneSample) {
            set(mc.options.keyInventory, false);
            inventoryHeldForOneSample = false;
        }

        // Throttle mouse forwarding to ~120 Hz. Unity samples per rendered frame and
        // Minecraft ticks at 20 Hz, so without this the mouse would move in visible steps.
        // Unity's mouse delta is +up; Minecraft's mouse Y is screen coordinates, +down. Measured
        // 2026-10-13: forwarding it unflipped made mouse-up look DOWN in Minecraft (13/13
        // samples), hidden on screen by an unnegated pitch copy in ULTRAKILL, until the
        // crosshair raycast placed blocks above the player when looking down. Flip it here,
        // once, at the boundary into Minecraft's convention.
        in.mouseDy = -in.mouseDy;
        if (in.mouseDx != 0f || in.mouseDy != 0f) {
            if (now - lastMouseMs >= 8) {
                lastMouseMs = now;
                forwardMouse(mc, in.mouseDx, in.mouseDy);
            }
        }

        // Camera turn, driven directly. See forwardMouse for why onMove cannot be used.
        accumulateMouse(in.mouseDx, in.mouseDy);

        applied++;
    }

    /**
     * Feed a mouse delta to Minecraft.
     *
     * <p>Kept, but it is NOT what turns the camera. {@code onMove} ignores the event unless
     * the window argument matches its own handle (0 is REJECTED: verified 2026-10-13, so
     * the call below, which passes 0, is a no-op on purpose; giving it the real handle would
     * make it accumulate on top of accumulateMouse and double the turning), and then branches on whether the
     * cursor is grabbed. In the branch we are always in, because we suppress the grab, it
     * does not accumulate the delta at all. It treats the argument as an ABSOLUTE cursor
     * position and differences it against a stored previous position:
     *
     * <pre>
     *  88: accumulatedDX
     *  93: dload_3            // the delta we passed
     *  95: getfield xpos       // minus the position it stored last time
     * 120: xpos = dload_3      // then stores our delta as if it were a position
     * </pre>
     *
     * <p>Feeding deltas in therefore differences them a second time, and smooth motion
     * cancels to nearly nothing. That is why the camera never turned: Minecraft's yaw sat
     * at -177.00 and its pitch at 74.10 for an entire session while ULTRAKILL was
     * demonstrably forwarding hundreds of mouse samples.
     *
     * <p>Leaving this in place keeps Minecraft's idea of the pointer position roughly sane
     * for anything that reads it for display, such as GUI element hit-testing.
     */
    private void forwardMouse(Minecraft mc, float dx, float dy) {
        virtualCursorX += dx;
        virtualCursorY += dy;
        try {
            // The cursor is not really being grabbed, so it must be reported as a position
            // for the branch above to difference correctly. Keeping a running total is what
            // turns ULTRAKILL's deltas back into the positions that branch expects.
            mc.mouseHandler.onMove(0L, virtualCursorX, virtualCursorY, dx, dy);
        } catch (Throwable t) {
            LOG.warn("[UltraCraft] mouse forward failed: {}", t.toString());
        }
    }

    private float virtualCursorX;
    private float virtualCursorY;

    /**
     * Turn the player by writing Minecraft's own mouse accumulators directly.
     *
     * <p>{@code MouseHandler.onMove} is unusable for this while the grab is suppressed, and
     * {@code MouseHandler} has no public method to turn the player. The accumulators are
     * private with no accessors, so they are written reflectively into the real instance:
     * {@code accumulatedDX} and {@code accumulatedDY}, the exact fields
     * {@code handleAccumulatedMovement} drains and passes to {@code turnPlayer}.
     *
     * <p>Writing the fields Minecraft itself writes is deliberate. It keeps Minecraft's turn
     * path, its smoothing, its sensitivity option and its pitch clamp entirely in charge,
     * so look speed and feel stay exactly vanilla and there is no second implementation of
     * "turn a player" to drift out of step with the game.
     *
     * <p>{@code handleAccumulatedMovement} is not gated on the cursor being grabbed, only on
     * {@code isWindowActive()}, which {@code WindowMixin} already forces true.
     */
    private void accumulateMouse(float dx, float dy) {
        if (!mouseAccumulatorReady) {
            if (!prepareMouseAccumulator()) return;
        }
        try {
            // Read-modify-write rather than assign: these accumulators are drained by
            // handleAccumulatedMovement, but only once per tick, while forwarded samples
            // arrive more often than that. Overwriting would discard the turns from every
            // sample since the last tick.
            MOUSE_ACCUM_DX.setDouble(mcMouseHandler, MOUSE_ACCUM_DX.getDouble(mcMouseHandler) + dx);
            MOUSE_ACCUM_DY.setDouble(mcMouseHandler, MOUSE_ACCUM_DY.getDouble(mcMouseHandler) + dy);
        } catch (Throwable t) {
            mouseAccumulatorReady = false;
            LOG.warn("[UltraCraft] could not accumulate mouse movement: {}", t.toString());
        }
    }

    private static final java.lang.reflect.Field MOUSE_ACCUM_DX = findField("accumulatedDX");
    private static final java.lang.reflect.Field MOUSE_ACCUM_DY = findField("accumulatedDY");

    private static java.lang.reflect.Field findField(String name) {
        try {
            java.lang.reflect.Field f = MouseHandler.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private Object mcMouseHandler;
    private boolean mouseAccumulatorReady;

    private boolean prepareMouseAccumulator() {
        if (MOUSE_ACCUM_DX == null || MOUSE_ACCUM_DY == null) {
            LOG.error("[UltraCraft] MouseHandler.accumulatedDX/DY not found. Minecraft's "
                    + "camera cannot be turned; a rename in this version will need the "
                    + "field names updated here.");
            return false;
        }
        mcMouseHandler = Minecraft.getInstance().mouseHandler;
        mouseAccumulatorReady = mcMouseHandler != null;
        if (mouseAccumulatorReady) {
            LOG.info("[UltraCraft] camera input path ready: writing Minecraft's own "
                    + "accumulatedDX/accumulatedDY");
        }
        return mouseAccumulatorReady;
    }

    /**
     * Replay a mouse button press or release through Minecraft's own input path.
     *
     * <p>MUST pass Minecraft's real window handle. Verified in the 26.3 bytecode (2026-10-13):
     * {@code onButton} begins {@code if (window == 0) return; if (window != handle) return;}.
     * This used to pass 0 under a comment claiming 0 meant "no particular window", so every
     * replayed press was dropped on the first line: no {@code KeyMapping.click}, so
     * {@code startAttack} (Creative's instant break) never ran. Only the separately set held
     * state worked, which is why holding mined and a block broke after "exactly 3-4 clicks":
     * each ~2-tick click let {@code continueAttack} count down Creative's 5-tick destroyDelay.
     * Measured with a temporary click probe (since removed): destroyDelay 5 -> 3 -> 1 -> break,
     * and no startAttack calls for forwarded presses.
     */
    private void forwardButton(Minecraft mc, int button, boolean pressed) {
        try {
            mc.mouseHandler.onButton(mc.getWindow().handle(), new MouseButtonInfo(button, 0), pressed ? 1 : 0);
        } catch (Throwable t) {
            LOG.warn("[UltraCraft] mouse button {} forward failed: {}", button, t.toString());
        }
    }

    private int lastHotbarSeq = -1;
    private float lastScroll = Float.NaN;

    /**
     * Hotbar keys 1-9 and the mouse wheel, pressed in ULTRAKILL (HUD milestone). Both arrive as
     * counters (a sequence number with the latest slot; a running wheel total), so a sample
     * Minecraft misses loses nothing. The first sample only records the baseline.
     *
     * <p>Keys select the slot directly. The wheel goes through Minecraft's own
     * {@code MouseHandler.onScroll}, which needs the REAL window handle: verified in the 26.3
     * bytecode, it starts with the same {@code window == 0 -> return} guard as onButton.
     */
    private void applyHotbarAndScroll(Minecraft mc, Bridge.InputState in) {
        dev.ultracraft.gui.GuiLayout.setScreen(mc, in.screenWidth, in.screenHeight);
        if (lastHotbarSeq < 0) {
            lastHotbarSeq = in.hotbarSeq;
        } else if (in.hotbarSeq != lastHotbarSeq) {
            lastHotbarSeq = in.hotbarSeq;
            // Inside a screen the number keys arrive as real key events and do what vanilla does
            // there (swap the hovered item into that slot); selecting here too would be wrong.
            if (mc.gui.screen() == null && mc.player != null && in.hotbarSlot >= 0 && in.hotbarSlot < 9) {
                mc.player.getInventory().setSelectedSlot(in.hotbarSlot);
            }
        }
        if (Float.isNaN(lastScroll)) {
            lastScroll = in.scrollTotal;
        } else if (in.scrollTotal != lastScroll) {
            double steps = in.scrollTotal - lastScroll;
            lastScroll = in.scrollTotal;
            try {
                mc.mouseHandler.onScroll(mc.getWindow().handle(), 0.0, steps);
            } catch (Throwable t) {
                LOG.warn("[UltraCraft] scroll forward failed: {}", t.toString());
            }
        }
    }

    private float lastCursorX = Float.NaN, lastCursorY = Float.NaN;

    /**
     * Mouse passthrough: while a Minecraft screen is open, ULTRAKILL sends its cursor in
     * Minecraft GUI pixels. Screens read the cursor as {@code xpos * guiScaledWidth / screenWidth}
     * (verified, MouseHandler.getScaledXPos), so xpos is set to the inverse, through
     * {@code onMove} with the REAL window handle (0 is rejected) so hovering and dragging reach
     * the screen. Its delta bookkeeping cannot turn the player: the turn gate is closed while a
     * screen is open (MouseHandlerMixin).
     */
    private void applyCursor(Minecraft mc, Bridge.InputState in) {
        if (!in.cursorActive || mc.gui.screen() == null || !dev.ultracraft.gui.GuiLayout.active()) {
            lastCursorX = lastCursorY = Float.NaN;
            return;
        }
        if (in.cursorX == lastCursorX && in.cursorY == lastCursorY) return;
        lastCursorX = in.cursorX;
        lastCursorY = in.cursorY;
        var w = mc.getWindow();
        double x = in.cursorX * (double) w.getScreenWidth() / dev.ultracraft.gui.GuiLayout.scaledWidth();
        double y = in.cursorY * (double) w.getScreenHeight() / dev.ultracraft.gui.GuiLayout.scaledHeight();
        try {
            mc.mouseHandler.onMove(w.handle(), x, y, 0, 0);
        } catch (Throwable t) {
            LOG.warn("[UltraCraft] cursor forward failed: {}", t.toString());
        }
    }

    /**
     * Keyboard passthrough: key and character events from ULTRAKILL (UK_EVENTS ring), replayed
     * through Minecraft's own KeyboardHandler with the REAL window handle (keyPress and charTyped
     * both start with the window == 0 -> return guard). So T opens chat, / the command line, E the
     * inventory, Esc (ULTRAKILL's ~) the pause menu, and inside a screen every key and typed
     * character reaches it, exactly as from Minecraft's own window.
     */
    private void applyEvents(Minecraft mc) {
        long handle = mc.getWindow().handle();
        for (int i = 0; i < 256; i++) {
            Bridge.Event e = bridge.popEvent();
            if (e == null) return;
            try {
                if (e.kind == Protocol.EV_KEY) {
                    // KeyEvent(scancode, SDL keycode, modifiers): bindings and isEscape read the
                    // scancode, text editing reads the keycode (shortcutKey); see generate.py.
                    mc.keyboardHandler.keyPress(handle, e.b & 0xFF,
                            new net.minecraft.client.input.KeyEvent((e.b >>> 22) & 0xFF, e.a, (e.b >>> 8) & 0x3FFF));
                } else if (e.kind == Protocol.EV_CHAR) {
                    mc.keyboardHandler.charTyped(handle, new net.minecraft.client.input.CharacterEvent(e.a));
                } else if (e.kind == Protocol.EV_MOUSE_BUTTON) {
                    // Middle click: pick block in the world, clone stack in creative screens.
                    forwardButton(mc, e.a, e.b == 1);
                }
            } catch (Throwable t) {
                LOG.warn("[UltraCraft] key event {} failed: {}", e.kind, t.toString());
            }
        }
    }

    private static void set(KeyMapping km, boolean down) {
        if (km != null && km.isDown() != down) {
            km.setDown(down);
        }
    }

    private boolean reportedFirstApply;
    private boolean reportedKeys;
    private boolean reportedMove;
    private Vec3 lastPos;

    /**
     * Log enough once to prove which link in the chain works, then stay quiet.
     *
     * <p>Three separate facts matter and none can be inferred from the others:
     * whether forwarded keys arrive at all, whether setDown survives Minecraft's own
     * input handling, and whether the player actually moves as a result.
     */
    private void reportFirstSample(Bridge.InputState in) {
        if (reportedFirstApply) return;
        reportedFirstApply = true;

        LOG.info("[UltraCraft] input: first sample received, keys=0x{} mouseDelta={},{} "
                        + "attack={} use={}",
                Integer.toHexString(in.keys), in.mouseDx, in.mouseDy, in.attack, in.use);
        // Raw bytes from this end of the bridge, next to the values decoded out of
        // them, so a reader-side offset mistake is visible rather than inferred.
        // ULTRAKILL logs the same slot from the other end at the same moment.
        LOG.info("[UltraCraft] input: raw slot bytes @ {}: {} | decoded keys@+{}={} "
                        + "mouseDx@+{}={} mouseDy@+{}={}",
                Protocol.OFFSET_INPUT,
                bridge.dumpInputSlot(),
                Protocol.SlotInput.OFF_KEYS, Integer.toHexString(in.keys),
                Protocol.SlotInput.OFF_MOUSEDX, in.mouseDx,
                Protocol.SlotInput.OFF_MOUSEDY, in.mouseDy);
    }

    /// <p>Reports state as it stands AFTER this tick's keys have been applied.
    private void reportApplied(Minecraft mc, Bridge.InputState in) {

        boolean anyKey = in.keys != 0 || in.attack || in.use;
        if (anyKey && !reportedKeys) {
            reportedKeys = true;
            LOG.info("[UltraCraft] input: keys=0x{} arrived; after setDown -> "
                            + "keyUp.isDown()={} keyLeft.isDown()={} keyJump.isDown()={}",
                    Integer.toHexString(in.keys),
                    mc.options.keyUp.isDown(),
                    mc.options.keyLeft.isDown(),
                    mc.options.keyJump.isDown());
        }

        reportButtons(mc, in);

        if (!reportedMove && lastPos != null) {
            Vec3 now = mc.player.position();
            if (now.distanceToSqr(lastPos) > 0.0004) {
                reportedMove = true;
                LOG.info("[UltraCraft] input: player MOVED to {},{},{} from {},{},{}",
                        round(now.x), round(now.y), round(now.z),
                        round(lastPos.x), round(lastPos.y), round(lastPos.z));
            }
        }
        lastPos = mc.player.position();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private boolean reportedAttack;
    private boolean reportedUse;
    private int buttonReports;

    /**
     * Log mouse button state, and everything that can silently stop it doing anything.
     *
     * <p>Left and right click failing had three independent possible causes that all
     * looked identical from the outside, and this reports the state of each one:
     *
     * <ul>
     *   <li>whether the button arrives from ULTRAKILL at all,
     *   <li>whether Minecraft's own key state survives being set,
     *   <li>whether the button press reached the click counter that discrete actions
     *       are gated on,
     *   <li>whether there is even a block to act on ({@code hitResult}), and
     *   <li>whether a screen is open, which suspends all of Minecraft's gameplay input.
     * </ul>
     *
     * <p>Logged on change rather than once, because a click is a one-frame event and a
     * single early sample would have missed it every time.
     *
     * <p>The current screen is {@code gui.screen()} in 26.3; there is no {@code screen}
     * field on Minecraft any more.
     */
    private void reportButtons(Minecraft mc, Bridge.InputState in) {
        if (in.attack && !wasAttackLogged || in.use && !wasUseLogged) {
            String hit = mc.hitResult == null ? "null" : mc.hitResult.getType().name();
            var screen = mc.gui.screen();
            LOG.info("[UltraCraft] input: button LMB={} RMB={} | keyAttack.isDown()={} "
                            + "keyUse.isDown()={} | clickCounter attack={} use={} | "
                            + "hitResult={} screen={}",
                    in.attack, in.use,
                    mc.options.keyAttack.isDown(), mc.options.keyUse.isDown(),
                    clickPending(mc.options.keyAttack), clickPending(mc.options.keyUse),
                    hit, screen == null ? "none" : screen.getClass().getSimpleName());

            if (++buttonReports <= 8) {
                wasAttackLogged |= in.attack;
                wasUseLogged |= in.use;
            }
        }
    }

    private boolean wasAttackLogged;
    private boolean wasUseLogged;

    /**
     * Read the private click counter without modifying it, for diagnostics only.
     *
     * <p>{@code KeyMapping.consumeClick()} both reads and clears it, so calling it here
     * would break the very thing being measured. {@code clickCount} is private and has
     * no getter, so this goes through reflection rather than an accessor mixin: a
     * failure here would be a missing diagnostic, never a gameplay failure, which is not
     * worth the fragility of a second mixin targeting the same class.
     */
    private static int clickPending(KeyMapping km) {
        try {
            java.lang.reflect.Field f = KeyMapping.class.getDeclaredField("clickCount");
            f.setAccessible(true);
            return f.getInt(km);
        } catch (Throwable t) {
            return -1;
        }
    }

    private final Bridge.InputState inputState = new Bridge.InputState();

    /**
     * Read the forwarded input, rejecting samples we have already applied.
     *
     * <p>ULTRAKILL publishes every rendered frame while Minecraft ticks at 20 Hz, so the
     * same sample would otherwise be applied several times over.
     */
    private Bridge.InputState readInput() {
        Bridge.InputState in = inputState;
        if (!bridge.readInput(in)) return null;
        if (in.tickTimestamp == lastApplied) return null;
        lastApplied = in.tickTimestamp;
        return in;
    }

    int appliedCount() {
        return applied;
    }
}