package com.starwake.client.wristcom;

import com.starwake.client.hud.FlightHud;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The Wristcom (G): a holographic wrist computer. A bar of app tiles on the left, the open app
 * on the right, a status line on top. It doesn't pause the game: like a mobiGlas, you use it
 * while the world keeps moving.
 */
public class WristcomScreen extends Screen {
	public static final int BG = 0xD0050B12, FRAME = 0xFF6FE3FF, DIM = 0x996FE3FF, FAINT = 0x306FE3FF, TEXT = 0xFFE9F6FF;
	private static final int BAR = 92;
	private static int lastApp;

	private final List<WristcomApp> apps = WristcomApps.all();
	private int current;
	private long openedAt;

	public WristcomScreen() {
		super(Component.translatable("screen.starwake.wristcom"));
		current = Mth.clamp(lastApp, 0, Math.max(0, apps.size() - 1));
	}

	/** Opens the Wristcom on the first app of the given type. */
	public static WristcomScreen openOn(Class<? extends WristcomApp> type) {
		WristcomScreen screen = new WristcomScreen();
		for (int i = 0; i < screen.apps.size(); i++) {
			if (type.isInstance(screen.apps.get(i))) {
				screen.current = i;
				lastApp = i;
			}
		}
		return screen;
	}

	/** Rebuilds the current app's widgets (new data arrived). */
	public void refresh() {
		rebuildWidgets();
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (currentApp().scrolled(scrollY)) return true;
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	public WristcomApp currentApp() {
		return apps.get(current);
	}

	/** Lets apps add widgets (buttons, sliders) inside their content area. */
	public <T extends AbstractWidget> T add(T widget) {
		return addRenderableWidget(widget);
	}

	public net.minecraft.client.gui.Font font() {
		return font;
	}

	// Panel geometry: a centred tablet, at most 460 x 270.
	private int panelW() { return Math.min(width - 20, 460); }
	private int panelH() { return Math.min(height - 20, 270); }
	private int panelX() { return (width - panelW()) / 2; }
	private int panelY() { return (height - panelH()) / 2; }

	@Override
	protected void init() {
		if (openedAt == 0) openedAt = System.currentTimeMillis();
		int x = panelX(), y = panelY();
		for (int i = 0; i < apps.size(); i++) {
			int index = i;
			WristcomApp app = apps.get(i);
			addRenderableWidget(Button.builder(Component.literal(app.glyph() + "  ").append(app.title()), b -> open(index))
					.bounds(x + 6, y + 24 + i * 24, BAR - 12, 20).build()).active = i != current;
		}
		int cx = x + BAR + 6, cy = y + 24, cw = panelW() - BAR - 12, ch = panelH() - 34;
		currentApp().init(this, cx, cy, cw, ch);
	}

	private void open(int index) {
		current = index;
		lastApp = index;
		rebuildWidgets();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		int x = panelX(), y = panelY(), w = panelW(), h = panelH();
		// Open animation: the frame grows in over 150 ms.
		float t = Mth.clamp((System.currentTimeMillis() - openedAt) / 150f, 0, 1);
		int grow = Math.round((1 - t) * 20);
		graphics.fill(x + grow, y + grow, x + w - grow, y + h - grow, BG);
		FlightHud.corners(graphics, x + grow - 2, y + grow - 2, w - 2 * grow + 4, h - 2 * grow + 4, FRAME);
		graphics.fill(x + BAR, y + 22, x + BAR + 1, y + h - 8, FAINT);
		graphics.fill(x + 6, y + 18, x + w - 6, y + 19, FAINT);
		// Faint scanlines for the hologram look.
		for (int sy = y + 2; sy < y + h; sy += 3) graphics.fill(x + 1, sy, x + w - 1, sy + 1, 0x08FFFFFF);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		int x = panelX(), y = panelY(), w = panelW(), h = panelH();
		graphics.text(font, Component.translatable("screen.starwake.wristcom"), x + 8, y + 6, FRAME, false);
		WristcomApp app = currentApp();
		graphics.text(font, app.title(), x + BAR + 6, y + 6, app.accent(), false);
		String clock = WristcomStatus.clock();
		graphics.text(font, clock, x + w - 8 - font.width(clock), y + 6, DIM, false);
		app.render(this, graphics, x + BAR + 6, y + 24, w - BAR - 12, h - 34, mouseX, mouseY, partialTick);
		graphics.text(font, Component.translatable("screen.starwake.wristcom.hint"), x + 8, y + h - 12, DIM, false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
