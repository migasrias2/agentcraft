package dev.agentcraft.client.gallery;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.ui.Guard;
import dev.agentcraft.hq.GalleryLayout;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.Vec3;

/**
 * The Gallery room (north-west annex of the HQ hall): framed paintings from real images on its walls.
 * Sources are the folder {@code ~/Pictures/AgentCraft Galeria} and the images agents handed you with
 * show_media (see {@link GalleryModel}); {@link GalleryRenderer} draws them, right-clicking a painting
 * opens the big view and right-clicking the lectern turns the page. Client-only; the room and its bays
 * are positions only ({@link GalleryLayout}), found by where you click, like the casino table.
 * Dev: {@code dev.gallery {page?}} and {@code dev.screen {open:"gallery"}}.
 */
public final class GalleryFeature {
	private GalleryFeature() {
	}

	public static void init() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || player.isSecondaryUseActive()) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos();
			Vec3 at = hit.getLocation();
			if (GalleryLayout.isLectern(pos.getX(), pos.getY(), pos.getZ())) {
				if (hand == InteractionHand.MAIN_HAND) {
					turnPage(Minecraft.getInstance());
				}
				return InteractionResult.FAIL;
			}
			GalleryLayout.Slot slot = GalleryLayout.slotAt(at.x, at.y, at.z);
			List<GalleryItem> page = GalleryModel.pageItems();
			if (slot == null || slot.index() >= page.size()) {
				return InteractionResult.PASS;
			}
			if (hand == InteractionHand.MAIN_HAND) {
				Minecraft mc = Minecraft.getInstance();
				mc.gui.setScreen(GalleryViewScreen.forItem(GalleryModel.items(), page.get(slot.index())));
			}
			return InteractionResult.FAIL;
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> Guard.run("gallery.render", () -> GalleryRenderer.render(ctx)));
		DevBridge.registerScreen("gallery", mc -> {
			List<GalleryItem> items = GalleryModel.items();
			GalleryItem first = items.stream().filter(i -> !i.isMedia()).findFirst().orElse(null);
			if (first == null) {
				throw new DevBridge.DevException("no image files in the gallery yet (put some in " + GalleryModel.FOLDER + ")");
			}
			return GalleryViewScreen.forItem(items, first);
		});
		DevBridge.register("dev.gallery", 10_000, "{page?} -> gallery items, page and what each bay shows; page turns to that page (0-based)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				int page = f.optInt("page", -1, -1, 100_000);
				return DevBridge.onClient(mc, () -> {
					if (page >= 0) {
						GalleryModel.setPage(page);
					}
					JsonObject o = new JsonObject();
					o.addProperty("folder", GalleryModel.FOLDER.toString());
					o.addProperty("items", GalleryModel.items().size());
					o.addProperty("page", GalleryModel.page());
					o.addProperty("pages", GalleryModel.pages());
					JsonArray bays = new JsonArray();
					for (int i = 0; i < GalleryModel.pageItems().size(); i++) {
						bays.add(i + ": " + GalleryModel.pageItems().get(i).title() + " | " + GalleryTextures.describe(i));
					}
					o.add("bays", bays);
					return o;
				});
			});
	}

	private static void turnPage(Minecraft mc) {
		GalleryModel.rescanSoon();
		GalleryModel.nextPage();
		if (mc.player != null) {
			int total = GalleryModel.items().size();
			String msg = total == 0 ? "The gallery is empty. Put images in Pictures/AgentCraft Galeria"
				: "Gallery page " + (GalleryModel.page() + 1) + " / " + GalleryModel.pages();
			mc.player.sendOverlayMessage(Component.literal(msg));
		}
	}
}
