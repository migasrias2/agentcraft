package dev.agentcraft.client.content;

import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.Protocol.ContentChannel;
import dev.agentcraft.client.foreman.Protocol.ContentVideo;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.hud.Toasts;
import dev.agentcraft.client.hud.UiBits;
import dev.agentcraft.client.ui.Guard;
import dev.agentcraft.hq.ContentLayout;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import org.jspecify.annotations.Nullable;

/**
 * The Content Room on the client: right-clicking its lectern opens the {@link ContentStudioScreen}, a toast says when
 * a video is ready for your review or has gone out, and a freshly added channel becomes the selected one.
 * Dev: {@code dev.screen {open:"content"}}.
 */
public final class ContentFeature {
	private ContentFeature() {
	}

	public static void init() {
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			if (!level.isClientSide() || player.isSecondaryUseActive()) {
				return InteractionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos();
			if (ContentLayout.isLectern(pos.getX(), pos.getY(), pos.getZ())) {
				if (hand == InteractionHand.MAIN_HAND) {
					open();
				}
				return InteractionResult.FAIL;
			}
			return InteractionResult.PASS;
		});
		DevBridge.registerScreen("content", mc -> new ContentStudioScreen());
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onContentChannel(@Nullable ContentChannel previous, ContentChannel channel) {
				if (previous == null && System.currentTimeMillis() - channel.createdAt() < 60_000) {
					ContentState.selectChannel(channel.id());
				}
			}

			@Override
			public void onContentVideo(@Nullable ContentVideo previous, ContentVideo video) {
				Guard.run("content.toast", () -> toast(previous, video));
			}
		});
	}

	public static void open() {
		Minecraft.getInstance().gui.setScreen(new ContentStudioScreen());
	}

	/** A toast when a video newly reaches a stage that matters to you (an idea to judge, a cut to review) or goes out. */
	private static void toast(@Nullable ContentVideo previous, ContentVideo v) {
		String before = previous == null ? "" : previous.status();
		if (before.equals(v.status()) || System.currentTimeMillis() - v.updatedAt() > 120_000) {
			return;
		}
		String text = switch (v.status()) {
			case "review" -> UiBits.agentName("reel") + ": \"" + v.title() + "\" is ready for your review (/content)";
			case "published" -> UiBits.agentName("cleo") + ": \"" + v.title() + "\" is live";
			case "failed" -> UiBits.agentName("cleo") + ": publishing \"" + v.title() + "\" failed (/content)";
			default -> null;
		};
		if (text != null) {
			Toasts.push(new Notify(v.status().equals("published") ? NotifyLevel.INFO : NotifyLevel.NEED_USER, text, null, System.currentTimeMillis()));
		}
	}
}
