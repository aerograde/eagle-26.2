package net.minecraft.client;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.mojang.logging.LogUtils;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputQuirks;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.util.Mth;
import net.minecraft.util.SmoothDouble;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Inventory;
import org.joml.Vector2i;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFWDropCallback;
import org.slf4j.Logger;

public class MouseHandler {
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final long DOUBLE_CLICK_THRESHOLD_MS = 250L;
   private final Minecraft minecraft;
   private boolean isLeftPressed;
   private boolean isMiddlePressed;
   private boolean isRightPressed;
   private double xpos;
   private double ypos;
   private MouseHandler.@Nullable LastClick lastClick;
   protected @MouseButtonInfo.MouseButton int lastClickButton;
   private int fakeRightMouse;
   private @Nullable MouseButtonInfo activeButton = null;
   private boolean ignoreFirstMove = true;
   private double mousePressedTime;
   private final SmoothDouble smoothTurnX = new SmoothDouble();
   private final SmoothDouble smoothTurnY = new SmoothDouble();
   private double accumulatedDX;
   private double accumulatedDY;
   private final ScrollWheelHandler scrollWheelHandler;
   private double lastHandleMovementTime = Double.MIN_VALUE;
   private boolean mouseGrabbed;

   public MouseHandler(final Minecraft minecraft) {
      this.minecraft = minecraft;
      this.scrollWheelHandler = new ScrollWheelHandler();
   }

   private void onButton(final long handle, final MouseButtonInfo rawButtonInfo, final @MouseButtonInfo.Action int action) {
      Window window = this.minecraft.getWindow();
      if (handle == window.handle()) {
         this.minecraft.getFramerateLimitTracker().onInputReceived();
         if (this.minecraft.gui.screen() != null) {
            this.minecraft.setLastInputType(InputType.MOUSE);
         }

         boolean pressed = action == 1;
         MouseButtonInfo buttonInfo = this.simulateRightClick(rawButtonInfo, pressed);
         if (pressed) {
            this.activeButton = buttonInfo;
            this.mousePressedTime = Blaze3D.getTime();
         } else if (this.activeButton != null) {
            this.activeButton = null;
         }

         if (this.minecraft.gui.overlay() == null) {
            if (pressed
               && this.minecraft.handleGlobalKeyPress(InputConstants.Type.MOUSE.getOrCreate(buttonInfo.button()), buttonInfo.hasControlDownWithQuirk())) {
               return;
            }

            if (this.minecraft.gui.screen() == null) {
               if (!this.mouseGrabbed && pressed) {
                  this.grabMouse();
               }
            } else {
               double xm = this.getScaledXPos(window);
               double ym = this.getScaledYPos(window);
               Screen screen = this.minecraft.gui.screen();
               MouseButtonEvent event = new MouseButtonEvent(xm, ym, buttonInfo);
               if (pressed) {
                  screen.afterMouseAction();

                  try {
                     long currentTime = Util.getMillis();
                     boolean doubleClick = this.lastClick != null
                        && currentTime - this.lastClick.time() < 250L
                        && this.lastClick.screen() == screen
                        && this.lastClickButton == event.button();
                     if (screen.mouseClicked(event, doubleClick)) {
                        this.lastClick = new MouseHandler.LastClick(currentTime, screen);
                        this.lastClickButton = buttonInfo.button();
                        return;
                     }
                  } catch (Throwable t) {
                     // The web log facade swallows nested throwable stacks, so a
                     // "mouseClicked event handler" ReportedException never showed its
                     // real cause. Print it directly to the console for diagnosis.
                     net.lax1dude.eaglercraft.v1_8.EagRuntime.debugPrintStackTraceToSTDERR(t);
                     CrashReport report = CrashReport.forThrowable(t, "mouseClicked event handler");
                     screen.fillCrashDetails(report);
                     CrashReportCategory mouseDetails = report.addCategory("Mouse");
                     this.fillMousePositionDetails(mouseDetails, window);
                     mouseDetails.setDetail("Button", event.button());
                     throw new ReportedException(report);
                  }
               } else {
                  try {
                     if (screen.mouseReleased(event)) {
                        return;
                     }
                  } catch (Throwable t) {
                     CrashReport report = CrashReport.forThrowable(t, "mouseReleased event handler");
                     screen.fillCrashDetails(report);
                     CrashReportCategory mouseDetails = report.addCategory("Mouse");
                     this.fillMousePositionDetails(mouseDetails, window);
                     mouseDetails.setDetail("Button", event.button());
                     throw new ReportedException(report);
                  }
               }
            }
         }

         if (this.minecraft.gui.screen() == null && this.minecraft.gui.overlay() == null) {
            if (buttonInfo.button() == 0) {
               this.isLeftPressed = pressed;
            } else if (buttonInfo.button() == 2) {
               this.isMiddlePressed = pressed;
            } else if (buttonInfo.button() == 1) {
               this.isRightPressed = pressed;
            }

            InputConstants.Key mouseKey = InputConstants.Type.MOUSE.getOrCreate(buttonInfo.button());
            KeyMapping.set(mouseKey, pressed);
            if (pressed) {
               KeyMapping.click(mouseKey);
            }
         }
      }
   }

   private MouseButtonInfo simulateRightClick(final MouseButtonInfo info, final boolean pressed) {
      if (InputQuirks.SIMULATE_RIGHT_CLICK_WITH_LONG_LEFT_CLICK && info.button() == 0) {
         if (pressed) {
            if ((info.modifiers() & 2) == 2) {
               this.fakeRightMouse++;
               return new MouseButtonInfo(1, info.modifiers());
            }
         } else if (this.fakeRightMouse > 0) {
            this.fakeRightMouse--;
            return new MouseButtonInfo(1, info.modifiers());
         }
      }

      return info;
   }

   public void fillMousePositionDetails(final CrashReportCategory category, final Window window) {
      category.setDetail(
         "Mouse location",
         () -> String.format(
            Locale.ROOT, "Scaled: (%f, %f). Absolute: (%f, %f)", getScaledXPos(window, this.xpos), getScaledYPos(window, this.ypos), this.xpos, this.ypos
         )
      );
      category.setDetail(
         "Screen size",
         () -> String.format(
            Locale.ROOT,
            "Scaled: (%d, %d). Absolute: (%d, %d). Scale factor of %d",
            window.getGuiScaledWidth(),
            window.getGuiScaledHeight(),
            window.getWidth(),
            window.getHeight(),
            window.getGuiScale()
         )
      );
   }

   private void onScroll(final long handle, final double xoffset, final double yoffset) {
      if (handle == this.minecraft.getWindow().handle()) {
         this.minecraft.getFramerateLimitTracker().onInputReceived();
         boolean discreteScroll = this.minecraft.options.discreteMouseScroll().get();
         double scrollSensitivity = this.minecraft.options.mouseWheelSensitivity().get();
         double scaledXOffset = (discreteScroll ? Math.signum(xoffset) : xoffset) * scrollSensitivity;
         double scaledYOffset = (discreteScroll ? Math.signum(yoffset) : yoffset) * scrollSensitivity;
         if (this.minecraft.gui.overlay() == null) {
            if (this.minecraft.gui.screen() != null) {
               double xm = this.getScaledXPos(this.minecraft.getWindow());
               double ym = this.getScaledYPos(this.minecraft.getWindow());
               this.minecraft.gui.screen().mouseScrolled(xm, ym, scaledXOffset, scaledYOffset);
               this.minecraft.gui.screen().afterMouseAction();
            } else if (this.minecraft.player != null) {
               Vector2i wheelXY = this.scrollWheelHandler.onMouseScroll(scaledXOffset, scaledYOffset);
               if (wheelXY.x == 0 && wheelXY.y == 0) {
                  return;
               }

               int wheel = wheelXY.y == 0 ? -wheelXY.x : wheelXY.y;
               if (this.minecraft.player.isSpectator()) {
                  if (this.minecraft.gui.hud.getSpectatorGui().isMenuActive()) {
                     this.minecraft.gui.hud.getSpectatorGui().onMouseScrolled(-wheel);
                  } else {
                     float speed = Mth.clamp(this.minecraft.player.getAbilities().getFlyingSpeed() + wheelXY.y * 0.005F, 0.0F, 0.2F);
                     this.minecraft.player.getAbilities().setFlyingSpeed(speed);
                  }
               } else {
                  Inventory inventory = this.minecraft.player.getInventory();
                  inventory.setSelectedSlot(ScrollWheelHandler.getNextScrollWheelSelection(wheel, inventory.getSelectedSlot(), Inventory.getSelectionSize()));
               }
            }
         }
      }
   }

   private void onDrop(final long handle, final List<Path> files, final int failedCount) {
      this.minecraft.getFramerateLimitTracker().onInputReceived();
      if (this.minecraft.gui.screen() != null) {
         this.minecraft.gui.screen().onFilesDrop(files);
      }

      if (failedCount > 0) {
         SystemToast.onFileDropFailure(this.minecraft, failedCount);
      }
   }

   /**
    * Eagler input seam (hosted mode): drain the PlatformInput event queues into the
    * same handlers the vanilla GLFW callbacks would have called. Runs on the main
    * thread right after RenderSystem.pollEvents() each frame. Queue Y is bottom-left
    * (eagler convention) and is un-flipped here; in grabbed mode the queue switches
    * to delta accumulation and absolute positions are synthesized.
    */
   public void eaglerPumpEvents() {
      final long handle = this.minecraft.getWindow().handle();
      while (net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseNext()) {
         final int button = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetEventButton();
         if (button >= 0) {
            final MouseButtonInfo buttonInfo = new MouseButtonInfo(
               button, net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetEventHostMods());
            final int action = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetEventButtonState() ? 1 : 0;
            final double eventX = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetEventX();
            final double eventY = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.getWindowHeight()
               - net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetEventY();
            // Browser Escape can release the DOM Pointer Lock before Minecraft has
            // observed a key event and released its logical grab. During that gap,
            // absolute cursor coordinates must not be rebased through onMove: the
            // next click-to-relock would turn the cursor's screen position into a
            // large camera delta. Treat either side's grab as authoritative until
            // both the game and browser agree that normal absolute UI input is back.
            final boolean grabbed = this.isMouseGrabbed()
               || net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.isMouseGrabbed();
            this.minecraft.execute(() -> {
               if (!grabbed) {
                  this.onMove(handle, eventX, eventY);
               }
               this.onButton(handle, buttonInfo, action);
            });
         } else {
            final float wheelY = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetEventDWheelF();
            final float wheelX = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetEventHostScrollX();
            if (wheelY != 0.0F || wheelX != 0.0F) {
               // A wheel event carries the browser cursor's absolute position even while
               // Pointer Lock is active. Feeding it through onMove turns that hidden
               // position into a camera delta, which makes every hotbar scroll snap the
               // player toward the same angle. Mouse movement has its own event/delta
               // path; wheel events must only scroll.
               this.minecraft.execute(() -> this.onScroll(handle, wheelX, wheelY));
            } else if (!this.isMouseGrabbed()
               && !net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.isMouseGrabbed()) {
               final double moveX = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetEventX();
               final double moveY = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.getWindowHeight()
                  - net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetEventY();
               this.minecraft.execute(() -> this.onMove(handle, moveX, moveY));
            }
         }
      }

      if (net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.isMouseGrabbed()) {
         final int dx = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetDX();
         final int dy = net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseGetDY();
         if (dx != 0 || dy != 0) {
            final double newX = this.xpos + dx;
            final double newY = this.ypos - dy;
            this.minecraft.execute(() -> this.onMove(handle, newX, newY));
         }
      }
   }

	/**
	 * Preserve the upstream 1.8 browser behavior when Chrome consumes Escape to
	 * leave Pointer Lock without sending the key to the game. The platform reports
	 * only a confirmed locked-to-unlocked edge; intentional releases performed by
	 * opening a screen clear that edge before the DOM callback arrives.
	 */
	public void eaglerHandleUnexpectedPointerLockLoss() {
		if (net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.consumeUnexpectedPointerLockLoss()
				&& this.mouseGrabbed && this.minecraft.player != null
				&& this.minecraft.gui.screen() == null && this.minecraft.gui.overlay() == null) {
			this.minecraft.pauseGame(false);
		}
	}

   public void setup(final Window window) {
      if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
         // Eagler input seam: events arrive via PlatformInput queues (eaglerPumpEvents);
         // hosted losses vs vanilla: drag-and-drop of files onto the window
         return;
      }
      InputConstants.setupMouseCallbacks(
         window, (window1, xpos, ypos) -> this.minecraft.execute(() -> this.onMove(window1, xpos, ypos)), (window1, button, action, mods) -> {
            MouseButtonInfo buttonInfo = new MouseButtonInfo(button, mods);
            this.minecraft.execute(() -> this.onButton(window1, buttonInfo, action));
         }, (window1, xoffset, yoffset) -> this.minecraft.execute(() -> this.onScroll(window1, xoffset, yoffset)), (window1, count, namesPtr) -> {
            List<Path> names = new ArrayList<>(count);
            int failedCount = 0;

            for (int i = 0; i < count; i++) {
               String name = GLFWDropCallback.getName(namesPtr, i);

               try {
                  names.add(Paths.get(name));
               } catch (InvalidPathException e) {
                  failedCount++;
                  LOGGER.error("Failed to parse path '{}'", name, e);
               }
            }

            if (!names.isEmpty()) {
               int finalFailedCount = failedCount;
               this.minecraft.execute(() -> this.onDrop(window1, names, finalFailedCount));
            }
         }
      );
   }

   private void onMove(final long handle, final double xpos, final double ypos) {
      if (handle == this.minecraft.getWindow().handle()) {
         if (this.ignoreFirstMove) {
            this.xpos = xpos;
            this.ypos = ypos;
            this.ignoreFirstMove = false;
         } else {
            if (this.minecraft.isWindowActive()) {
               this.accumulatedDX = this.accumulatedDX + (xpos - this.xpos);
               this.accumulatedDY = this.accumulatedDY + (ypos - this.ypos);
            }

            this.xpos = xpos;
            this.ypos = ypos;
         }
      }
   }

   public void handleAccumulatedMovement() {
      double time = Blaze3D.getTime();
      double mousea = time - this.lastHandleMovementTime;
      this.lastHandleMovementTime = time;
      if (this.minecraft.isWindowActive()) {
         Screen screen = this.minecraft.gui.screen();
         boolean mouseMoved = this.accumulatedDX != 0.0 || this.accumulatedDY != 0.0;
         if (mouseMoved) {
            this.minecraft.getFramerateLimitTracker().onInputReceived();
         }

         if (screen != null && this.minecraft.gui.overlay() == null && mouseMoved) {
            Window window = this.minecraft.getWindow();
            double xm = this.getScaledXPos(window);
            double ym = this.getScaledYPos(window);

            try {
               screen.mouseMoved(xm, ym);
            } catch (Throwable t) {
               CrashReport report = CrashReport.forThrowable(t, "mouseMoved event handler");
               screen.fillCrashDetails(report);
               CrashReportCategory mouseDetails = report.addCategory("Mouse");
               this.fillMousePositionDetails(mouseDetails, window);
               throw new ReportedException(report);
            }

            if (this.activeButton != null && this.mousePressedTime > 0.0) {
               double dx = getScaledXPos(window, this.accumulatedDX);
               double dy = getScaledYPos(window, this.accumulatedDY);

               try {
                  screen.mouseDragged(new MouseButtonEvent(xm, ym, this.activeButton), dx, dy);
               } catch (Throwable t) {
                  CrashReport report = CrashReport.forThrowable(t, "mouseDragged event handler");
                  screen.fillCrashDetails(report);
                  CrashReportCategory mouseDetails = report.addCategory("Mouse");
                  this.fillMousePositionDetails(mouseDetails, window);
                  throw new ReportedException(report);
               }
            }

            screen.afterMouseMove();
         }

         if (this.isMouseGrabbed() && this.minecraft.player != null) {
            this.turnPlayer(mousea);
         }
      }

      this.accumulatedDX = 0.0;
      this.accumulatedDY = 0.0;
   }

   public static double getScaledXPos(final Window window, final double x) {
      return x * window.getGuiScaledWidth() / window.getScreenWidth();
   }

   public double getScaledXPos(final Window window) {
      return getScaledXPos(window, this.xpos);
   }

   public static double getScaledYPos(final Window window, final double y) {
      return y * window.getGuiScaledHeight() / window.getScreenHeight();
   }

   public double getScaledYPos(final Window window) {
      return getScaledYPos(window, this.ypos);
   }

   private void turnPlayer(final double mousea) {
      double ss = this.minecraft.options.sensitivity().get() * 0.6F + 0.2F;
      double sensitivityMod = ss * ss * ss;
      double sens = sensitivityMod * 8.0;
      double xo;
      double yo;
      if (this.minecraft.options.smoothCamera) {
         double dx = this.smoothTurnX.getNewDeltaValue(this.accumulatedDX * sens, mousea * sens);
         double dy = this.smoothTurnY.getNewDeltaValue(this.accumulatedDY * sens, mousea * sens);
         xo = dx;
         yo = dy;
      } else if (this.minecraft.options.getCameraType().isFirstPerson() && this.minecraft.player.isScoping()) {
         this.smoothTurnX.reset();
         this.smoothTurnY.reset();
         xo = this.accumulatedDX * sensitivityMod;
         yo = this.accumulatedDY * sensitivityMod;
      } else {
         this.smoothTurnX.reset();
         this.smoothTurnY.reset();
         xo = this.accumulatedDX * sens;
         yo = this.accumulatedDY * sens;
      }

      this.minecraft.getTutorial().onMouse(xo, yo);
      if (this.minecraft.player != null) {
         this.minecraft.player.turn(this.minecraft.options.invertMouseX().get() ? -xo : xo, this.minecraft.options.invertMouseY().get() ? -yo : yo);
      }
   }

   public boolean isLeftPressed() {
      return this.isLeftPressed;
   }

   public boolean isMiddlePressed() {
      return this.isMiddlePressed;
   }

   public boolean isRightPressed() {
      return this.isRightPressed;
   }

   public double xpos() {
      return this.xpos;
   }

   public double ypos() {
      return this.ypos;
   }

   /** Feed an absolute native touch pointer into the normal screen mouse path. */
   public void eaglerTouchPointer(final double x, final double y, final @Nullable Boolean pressed) {
      long handle = this.minecraft.getWindow().handle();
      this.onMove(handle, x, y);
      if (pressed != null) {
         this.onButton(handle, new MouseButtonInfo(0, 0), pressed.booleanValue() ? 1 : 0);
      }
   }

   /** Feed look-pad motion through vanilla sensitivity/smoothing this frame. */
   public void eaglerTouchLookDelta(final double dx, final double dy) {
      this.accumulatedDX += dx;
      this.accumulatedDY += dy;
   }

   public void setIgnoreFirstMove() {
      this.ignoreFirstMove = true;
   }

   public boolean isMouseGrabbed() {
      return this.mouseGrabbed;
   }

   public void grabMouse() {
      if (this.minecraft.isWindowActive()) {
         if (!this.mouseGrabbed) {
            if (InputQuirks.RESTORE_KEY_STATE_AFTER_MOUSE_GRAB) {
               KeyMapping.setAll();
            }

            this.mouseGrabbed = true;
            this.xpos = this.minecraft.getWindow().getScreenWidth() / 2;
            this.ypos = this.minecraft.getWindow().getScreenHeight() / 2;
            if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
               net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseSetGrabbed(true);
            } else {
               InputConstants.grabOrReleaseMouse(this.minecraft.getWindow(), 212995, this.xpos, this.ypos);
            }
            this.minecraft.gui.setScreen(null);
            this.minecraft.missTime = 10000;
            this.ignoreFirstMove = true;
         }
      }
   }

   public void releaseMouse() {
      if (this.mouseGrabbed) {
         this.mouseGrabbed = false;
         this.xpos = this.minecraft.getWindow().getScreenWidth() / 2;
         this.ypos = this.minecraft.getWindow().getScreenHeight() / 2;
         if (net.lax1dude.eaglercraft.v1_8.minecraft.EaglerHosted.isActive()) {
            net.lax1dude.eaglercraft.v1_8.internal.PlatformInput.mouseSetGrabbed(false);
         } else {
            InputConstants.grabOrReleaseMouse(this.minecraft.getWindow(), 212993, this.xpos, this.ypos);
         }
      }
   }

   public void cursorEntered() {
      this.ignoreFirstMove = true;
   }

   public void drawDebugMouseInfo(final Font font, final GuiGraphicsExtractor graphics) {
      Window window = this.minecraft.getWindow();
      double x = this.getScaledXPos(window);
      double y = this.getScaledYPos(window) - 8.0;
      String text = String.format(Locale.ROOT, "%.0f,%.0f", x, y);
      graphics.text(font, text, (int)x, (int)y, -1);
   }

   private record LastClick(long time, Screen screen) {
   }
}
