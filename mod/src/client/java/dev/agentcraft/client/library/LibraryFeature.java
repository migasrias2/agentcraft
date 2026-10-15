package dev.agentcraft.client.library;

import com.google.gson.JsonObject;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.MemoryEntry;
import dev.agentcraft.client.world.StationInteractions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.level.block.Blocks;
import dev.agentcraft.client.foreman.Protocol;
import org.jspecify.annotations.Nullable;

/**
 * Memory library (Phase 3, library specialist).
 *
 * <ul>
 *   <li>{@link LibraryScreen}: two-pane reader of {@code Foreman.state().memory()} (scope tabs, the
 *       lead's plan pinned, markdown bodies, live task dots in the plan).</li>
 *   <li>{@link MemoryArchiveRenderer}: shelf labels (scope plate with the note count, one note
 *       title per further block of a row).</li>
 *   <li>Right-click: memory archive opens its scope (binding; empty = all) at the note its label
 *       shows; memory catalog and lecterns open the library at the plan.</li>
 *   <li>QA: {@code dev.screen {open:"library"}}, {@code dev.library {scope?, memoryId?, scroll?}}
 *       (opens / drives it, returns its state), {@code dev.library.state}.</li>
 * </ul>
 */
public final class LibraryFeature {
	private LibraryFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.MEMORY_ARCHIVE, ctx -> new MemoryArchiveRenderer());
		MemoryIndex.init();
		DevBridge.registerScreen("library", mc -> new LibraryScreen(null, null));
		DevBridge.registerScreen("doc", mc -> {
			Protocol.Doc d = DocScreen.newest();
			if (d == null) {
				throw new DevBridge.DevException("no handed-over documents yet (hand_over, or dev.foreman.inject a doc.upsert)");
			}
			return new DocScreen(d.id());
		});
		StationInteractions.onUse(ModBlocks.MEMORY_ARCHIVE, (player, pos, state, be) -> {
			String scope = be == null ? "" : be.binding();
			int k = MemoryArchiveRenderer.rowIndex(player.level(), pos, state, scope);
			MemoryEntry e = MemoryArchiveRenderer.entryAt(k, scope);
			open(scope, e == null ? null : e.id());
		});
		StationInteractions.onUse(ModBlocks.MEMORY_CATALOG, (player, pos, state, be) -> open(null, null));
		StationInteractions.onUse(Blocks.LECTERN, (player, pos, state, be) -> {
			MemoryEntry plan = MemoryIndex.plan();
			open(null, plan == null ? null : plan.id());
		});
		registerDev();
	}

	/** Open the library; {@code scope} null/empty = all notes, {@code memoryId} null = the plan / newest. */
	public static void open(@Nullable String scope, @Nullable String memoryId) {
		Minecraft.getInstance().gui.setScreen(new LibraryScreen(scope, memoryId));
	}

	private static void registerDev() {
		DevBridge.register("dev.library", 10_000,
			"{scope?: all|shared|<agentId>, memoryId?, scroll?, open?} -> opens (or drives the open) memory library and returns its state",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String scope = f.has("scope") ? f.str("scope") : null;
				String memoryId = f.has("memoryId") ? f.str("memoryId") : null;
				Double scroll = f.has("scroll") ? f.optNum("scroll", 0, 0, 10_000_000) : null;
				boolean forceOpen = f.optBool("open", false);
				return DevBridge.onClient(mc, () -> {
					Screen cur = mc.gui.screen();
					LibraryScreen screen;
					if (forceOpen || !(cur instanceof LibraryScreen)) {
						screen = new LibraryScreen(scope == null || scope.equals("all") ? null : scope, memoryId);
						mc.gui.setScreen(screen);
					} else {
						screen = (LibraryScreen) cur;
					}
					screen.applyDev(scope, memoryId, scroll == null ? null : scroll.floatValue());
					return screen.stateJson();
				});
			});
		DevBridge.register("dev.library.state", 5_000, "{} -> state of the open memory library ({open:false} when none)",
			(req, mc) -> DevBridge.onClient(mc, () -> {
				if (mc.gui.screen() instanceof LibraryScreen l) {
					JsonObject o = l.stateJson();
					o.addProperty("open", true);
					return o;
				}
				JsonObject o = new JsonObject();
				o.addProperty("open", false);
				o.addProperty("notes", MemoryIndex.count(MemoryIndex.ALL));
				return o;
			}));
	}
}
