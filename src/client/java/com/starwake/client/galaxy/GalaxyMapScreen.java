package com.starwake.client.galaxy;

import com.starwake.client.hud.FlightHud;
import com.starwake.galaxy.Galaxy;
import com.starwake.galaxy.SolarSystem;
import com.starwake.galaxy.StarClass;
import com.starwake.network.JumpTargetPayload;
import com.starwake.network.SystemInfoPayload;
import com.starwake.ship.ShipEntity;
import com.starwake.ship.SpaceTravel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The galaxy map (M6, key M, or from the Wristcom Starmap): a holographic 3D view of the
 * neighbouring stars. Left-drag orbits the view, right-drag pans, scroll zooms; click a star to
 * see its system (class, temperature, luminosity, distance and its planets, fetched from the
 * server) and, when piloting a ship in space, to set it as the jump target. F centres on the
 * selected star, H on where you are.
 *
 * <p>Stars are generated client-side from the galaxy seed ({@link Galaxy}); they're drawn as
 * projected points sized by luminosity, faded with depth. The jump range is a ring around you
 * and in-range stars are brighter.
 */
public class GalaxyMapScreen extends Screen {
	private static final int BG = 0xE0050A12, GRID = 0x2246C8FF, TEXT = 0xFFE6F2FF, DIM = 0xFF7F95AA, ACCENT = 0xFF55D8FF,
			HERE = 0xFF7CFFB2, SELECT = 0xFFFFD27A, RANGE = 0x8055D8FF;
	private static final int PANEL_W = 170;

	private Vec3 focus;
	private float yaw = 30, pitch = 25;
	private double zoom = 22;
	private Galaxy.@Nullable Star selected;
	private Galaxy.@Nullable Star hovered;
	private List<Galaxy.Star> stars = List.of();
	private Vec3 loadedFor = new Vec3(Double.NaN, 0, 0);
	private double loadedZoom;
	private @Nullable Button jumpButton;

	/** A star projected to the screen this frame. */
	private record Projected(Galaxy.Star star, float x, float y, float depth, float size) {}

	private List<Projected> projected = List.of();

	public GalaxyMapScreen() {
		super(Component.translatable("screen.starwake.galaxy"));
		this.focus = GalaxyClient.herePosition();
		this.selected = GalaxyClient.here();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		int px = width - PANEL_W - 8;
		jumpButton = addRenderableWidget(Button.builder(Component.translatable("screen.starwake.galaxy.set_jump"), b -> {
			if (selected != null) ClientPlayNetworking.send(new JumpTargetPayload(selected.id()));
		}).bounds(px, height - 30, PANEL_W, 20).build());
		updateButton();
	}

	private void updateButton() {
		if (jumpButton == null) return;
		ShipEntity ship = FlightHud.pilotedShip();
		Minecraft minecraft = Minecraft.getInstance();
		boolean inSpace = minecraft.level != null && SpaceTravel.isSpace(minecraft.level);
		Galaxy.Star here = GalaxyClient.here();
		jumpButton.active = ship != null && inSpace && selected != null && here != null && !selected.id().equals(here.id());
		jumpButton.visible = selected != null;
	}

	private @Nullable ShipEntity ship() {
		return FlightHud.pilotedShip();
	}

	// ---------------------------------------------------------------------------------------
	// Input
	// ---------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// The map area first (it has no widgets); the side panel's button goes to super.
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && event.x() < width - PANEL_W - 14) {
			Projected hit = pick(event.x(), event.y());
			if (hit != null) {
				selected = hit.star();
				if (doubleClick) focus = selected.position();
				updateButton();
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			yaw += (float) dx * 0.5f;
			pitch = Mth.clamp(pitch + (float) dy * 0.5f, -89, 89);
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			// Pan in the view plane, scaled to the zoom.
			double scale = zoom / (Math.min(width, height) * 0.45);
			float cy = Mth.cos((float) Math.toRadians(yaw)), sy = Mth.sin((float) Math.toRadians(yaw));
			Vec3 right = new Vec3(cy, 0, sy);
			Vec3 up = new Vec3(0, 1, 0);
			focus = focus.subtract(right.scale(dx * scale)).add(up.scale(dy * scale));
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		zoom = Mth.clamp(zoom * Math.pow(0.88, scrollY), 4, 160);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_F && selected != null) {
			focus = selected.position();
			return true;
		}
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_H) {
			focus = GalaxyClient.herePosition();
			return true;
		}
		if (key == com.mojang.blaze3d.platform.InputConstants.KEY_M) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	private @Nullable Projected pick(double mx, double my) {
		Projected best = null;
		double bestD = 9 * 9;
		for (Projected p : projected) {
			double d = (p.x() - mx) * (p.x() - mx) + (p.y() - my) * (p.y() - my);
			if (d < bestD) {
				bestD = d;
				best = p;
			}
		}
		return best;
	}

	// ---------------------------------------------------------------------------------------
	// Projection
	// ---------------------------------------------------------------------------------------

	/** Galaxy point -> {screen x, screen y, depth}, or null behind the camera. */
	private float @Nullable [] project(Vec3 p) {
		double x = p.x - focus.x, y = p.y - focus.y, z = p.z - focus.z;
		double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
		double x1 = x * Math.cos(ry) + z * Math.sin(ry), z1 = -x * Math.sin(ry) + z * Math.cos(ry);
		double y2 = y * Math.cos(rp) - z1 * Math.sin(rp), z2 = y * Math.sin(rp) + z1 * Math.cos(rp);
		double camera = zoom * 2.4;
		double depth = z2 + camera;
		if (depth < 0.5) return null;
		double f = Math.min(width - PANEL_W, height) * 1.05;
		int cx = (width - PANEL_W) / 2, cy = height / 2;
		return new float[] {(float) (cx + x1 * f / depth), (float) (cy - y2 * f / depth), (float) depth};
	}

	private void loadStars() {
		if (focus.distanceTo(loadedFor) < zoom * 0.25 && Math.abs(loadedZoom - zoom) < zoom * 0.2) return;
		stars = Galaxy.within(focus, Math.min(zoom * 1.1, 80));
		loadedFor = focus;
		loadedZoom = zoom;
	}

	// ---------------------------------------------------------------------------------------
	// Drawing
	// ---------------------------------------------------------------------------------------

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		g.fill(0, 0, width, height, BG);
		for (int y = 0; y < height; y += 3) g.fill(0, y, width, y + 1, 0x06FFFFFF);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		Font font = this.font;
		if (!Galaxy.configured()) {
			g.centeredText(font, Component.translatable("screen.starwake.galaxy.no_data"), width / 2, height / 2, DIM);
			return;
		}
		loadStars();
		Galaxy.Star here = GalaxyClient.here();
		Vec3 herePos = here == null ? Vec3.ZERO : here.position();
		ShipEntity ship = ship();
		double range = ship == null ? 0 : ship.jumpRange();

		g.enableScissor(0, 0, width - PANEL_W - 14, height);
		drawGrid(g, herePos);
		if (range > 0) drawRangeRing(g, herePos, range);

		// Project, then draw far to near.
		List<Projected> list = new ArrayList<>();
		for (Galaxy.Star s : stars) {
			float[] p = project(s.position());
			if (p == null) continue;
			double brightness = Math.log10(Math.max(1.0e-5, s.luminosity())) + 5; // 0..~10
			float size = (float) Mth.clamp(0.4 + brightness * 0.22, 0.5, 3.0) * (float) Mth.clamp(zoom * 2.4 / p[2], 0.6, 1.6);
			list.add(new Projected(s, p[0], p[1], p[2], size));
		}
		list.sort(Comparator.comparingDouble(Projected::depth).reversed());
		projected = list;
		hovered = pick(mouseX, mouseY) == null ? null : pick(mouseX, mouseY).star();

		// Route line to the selected star.
		if (selected != null && here != null && !selected.id().equals(here.id())) dottedLine(g, herePos, selected.position(), SELECT);

		for (Projected p : list) {
			Galaxy.Star s = p.star();
			float fade = (float) Mth.clamp(1.5 - p.depth() / (zoom * 2.6), 0.15, 1.0);
			boolean inRange = range > 0 && s.position().distanceTo(herePos) <= range;
			int color = ARGB.color((int) (255 * fade * (range > 0 && !inRange ? 0.55f : 1f)), s.color());
			int r = Math.round(p.size());
			int x = Math.round(p.x()), y = Math.round(p.y());
			if (r <= 1) {
				g.fill(x, y, x + 1 + r, y + 1 + r, color);
			} else {
				g.fill(x - r, y - r + 1, x + r, y + r - 1, color);
				g.fill(x - r + 1, y - r, x + r - 1, y + r, color);
			}
			if (s.companion().isPresent()) g.fill(x + r + 1, y - 1, x + r + 3, y + 1, ARGB.color((int) (200 * fade), s.companion().get().color()));
		}

		// Markers and labels (collision-avoiding).
		List<int[]> placed = new ArrayList<>();
		for (int i = list.size() - 1; i >= 0; i--) {
			Projected p = list.get(i);
			Galaxy.Star s = p.star();
			boolean isHere = here != null && s.id().equals(here.id());
			boolean isSelected = selected != null && s.id().equals(selected.id());
			boolean isHover = hovered != null && s.id().equals(hovered.id());
			int x = Math.round(p.x()), y = Math.round(p.y());
			if (isHere) bracket(g, x, y, 6, HERE);
			if (isSelected) bracket(g, x, y, 8, SELECT);
			if (isHover && !isSelected) bracket(g, x, y, 5, TEXT);
			boolean near = s.position().distanceTo(focus) < zoom * 0.45;
			if (!(isHere || isSelected || isHover || (near && zoom < 40))) continue;
			String label = s.name();
			int w = font.width(label);
			int ly = y + 6;
			if (overlaps(placed, x - w / 2, ly, w)) continue;
			placed.add(new int[] {x - w / 2 - 1, ly - 1, x + w / 2 + 1, ly + 9});
			g.text(font, label, x - w / 2, ly, isHere ? HERE : isSelected ? SELECT : DIM, false);
		}
		g.disableScissor();

		drawPanel(g, here);
		drawHelp(g);
	}

	private void drawGrid(GuiGraphicsExtractor g, Vec3 centre) {
		// A faint plane through your star: rings every 5 ly.
		for (int ring = 5; ring <= Math.min(80, zoom * 1.5); ring += 5) {
			int steps = 72;
			for (int i = 0; i < steps; i++) {
				double a = Math.PI * 2 * i / steps;
				float[] p = project(centre.add(Math.cos(a) * ring, 0, Math.sin(a) * ring));
				if (p != null) g.fill(Math.round(p[0]), Math.round(p[1]), Math.round(p[0]) + 1, Math.round(p[1]) + 1, GRID);
			}
		}
	}

	private void drawRangeRing(GuiGraphicsExtractor g, Vec3 centre, double range) {
		int steps = 120;
		for (int i = 0; i < steps; i++) {
			double a = Math.PI * 2 * i / steps;
			float[] p = project(centre.add(Math.cos(a) * range, 0, Math.sin(a) * range));
			if (p != null) g.fill(Math.round(p[0]), Math.round(p[1]), Math.round(p[0]) + 2, Math.round(p[1]) + 1, RANGE);
		}
	}

	private void dottedLine(GuiGraphicsExtractor g, Vec3 from, Vec3 to, int color) {
		int steps = 60;
		for (int i = 0; i <= steps; i += 2) {
			float[] p = project(from.lerp(to, i / (double) steps));
			if (p != null) g.fill(Math.round(p[0]), Math.round(p[1]), Math.round(p[0]) + 1, Math.round(p[1]) + 1, color);
		}
	}

	private void drawPanel(GuiGraphicsExtractor g, Galaxy.@Nullable Star here) {
		Font font = this.font;
		int x = width - PANEL_W - 8, y = 8, w = PANEL_W;
		g.fill(x - 6, 0, width, height, 0xC0081018);
		g.fill(x - 6, 0, x - 5, height, 0x6055D8FF);
		g.text(font, Component.translatable("screen.starwake.galaxy"), x, y, ACCENT, false);
		y += 14;
		if (here != null) {
			g.text(font, Component.translatable("screen.starwake.galaxy.here", here.name()), x, y, HERE, false);
			y += 12;
		}
		if (selected == null) {
			g.text(font, Component.translatable("screen.starwake.galaxy.pick"), x, y, DIM, false);
			return;
		}
		Galaxy.Star s = selected;
		y += 4;
		g.text(font, s.name(), x, y, SELECT, false);
		y += 11;
		StarClass cls = Galaxy.starClass(s.starClass());
		if (cls != null) {
			g.text(font, cls.name(), x, y, TEXT, false);
			y += 10;
		}
		g.text(font, String.format("%,d K  ·  %s L☉", Math.round(s.temperature()), luminosity(s.luminosity())), x, y, DIM, false);
		y += 10;
		g.text(font, String.format("%.2f M☉  ·  %.2f R☉", s.mass(), s.radius()), x, y, DIM, false);
		y += 10;
		if (s.companion().isPresent()) {
			StarClass c2 = Galaxy.starClass(s.companion().get().starClass());
			g.text(font, Component.translatable("screen.starwake.galaxy.binary", c2 == null ? Component.literal("?") : c2.name()), x, y, DIM, false);
			y += 10;
		}
		if (here != null) {
			double ly = s.distanceTo(here);
			ShipEntity ship = ship();
			boolean reach = ship != null && ship.jumpRange() >= ly;
			g.text(font, Component.translatable("screen.starwake.galaxy.distance", String.format("%.2f", ly)), x, y,
					ship == null ? TEXT : reach ? HERE : 0xFFFF6A4D, false);
			y += 10;
		}
		y += 4;
		var info = GalaxyClient.info(s.id());
		g.text(font, Component.translatable("screen.starwake.galaxy.planets", info.map(List::size).orElse(s.planets())), x, y, ACCENT, false);
		y += 11;
		if (info.isEmpty()) {
			g.text(font, Component.translatable("screen.starwake.galaxy.loading"), x, y, DIM, false);
		} else {
			for (SystemInfoPayload.PlanetInfo p : info.get()) {
				if (y > height - 58) {
					g.text(font, "...", x + 8, y, DIM, false);
					break;
				}
				g.fill(x, y + 2, x + 5, y + 7, p.color() | 0xFF000000);
				String meta = p.archetype().map(Component::getString).orElse("").replace(" world", "")
						+ "  " + Math.round(p.temperature()) + "°" + (p.breathable() ? "  air" : "");
				int metaW = font.width(meta);
				g.text(font, font.plainSubstrByWidth(p.name().getString(), w - 12 - metaW), x + 8, y, TEXT, false);
				g.text(font, meta, x + w - metaW, y, p.breathable() ? HERE : DIM, false);
				y += 10;
			}
		}
		updateButton();
		ShipEntity ship = ship();
		if (ship != null && selected != null && selected.id().equals(ship.jumpTargetId())) {
			g.text(font, Component.translatable("screen.starwake.galaxy.is_target"), x, height - 44, SELECT, false);
		}
	}

	private void drawHelp(GuiGraphicsExtractor g) {
		var lines = font.split(Component.translatable("screen.starwake.galaxy.help"), width - PANEL_W - 30);
		for (int i = 0; i < lines.size(); i++) g.text(font, lines.get(i), 6, 6 + i * 10, DIM, false);
	}

	private static String luminosity(double l) {
		if (l >= 100) return String.format("%,.0f", l);
		if (l >= 1) return String.format("%.2f", l);
		if (l >= 0.001) return String.format("%.4f", l);
		return String.format("%.1e", l);
	}

	private static boolean overlaps(List<int[]> placed, int x, int y, int w) {
		for (int[] r : placed) if (x < r[2] && x + w > r[0] && y < r[3] && y + 9 > r[1]) return true;
		return false;
	}

	private static void bracket(GuiGraphicsExtractor g, int x, int y, int s, int color) {
		int l = Math.max(2, s / 2);
		g.fill(x - s, y - s, x - s + l, y - s + 1, color);
		g.fill(x - s, y - s, x - s + 1, y - s + l, color);
		g.fill(x + s - l + 1, y - s, x + s + 1, y - s + 1, color);
		g.fill(x + s, y - s, x + s + 1, y - s + l, color);
		g.fill(x - s, y + s, x - s + l, y + s + 1, color);
		g.fill(x - s, y + s - l + 1, x - s + 1, y + s + 1, color);
		g.fill(x + s - l + 1, y + s, x + s + 1, y + s + 1, color);
		g.fill(x + s, y + s - l + 1, x + s + 1, y + s + 1, color);
	}
}
