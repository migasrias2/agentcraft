package dev.agentcraft.client.casino;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/** The chips and stats kept between sessions in {@code config/agentcraft-blackjack.json}. Never throws. */
public final class BlackjackSave {
	private static final String FILE = "agentcraft-blackjack.json";

	/** What was on disk (or the defaults). */
	public record Saved(int balance, Blackjack.Stats stats) {
	}

	private BlackjackSave() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE);
	}

	public static Saved load() {
		Blackjack.Stats stats = new Blackjack.Stats();
		int balance = Blackjack.START_BALANCE;
		try {
			Path f = file();
			if (Files.isRegularFile(f)) {
				JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
				balance = Math.max(0, intOf(o, "balance", balance));
				stats.hands = Math.max(0, intOf(o, "hands", 0));
				stats.won = Math.max(0, intOf(o, "won", 0));
				stats.lost = Math.max(0, intOf(o, "lost", 0));
				stats.biggestWin = Math.max(0, intOf(o, "biggestWin", 0));
			}
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Blackjack: could not read {}, starting fresh", FILE, e);
		}
		return new Saved(balance, stats);
	}

	public static void save(Blackjack game) {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("balance", game.savedBalance());
			o.addProperty("hands", game.stats().hands);
			o.addProperty("won", game.stats().won);
			o.addProperty("lost", game.stats().lost);
			o.addProperty("biggestWin", game.stats().biggestWin);
			Path f = file();
			Files.createDirectories(f.getParent());
			Path tmp = f.resolveSibling(FILE + ".tmp");
			Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Blackjack: could not save {}", FILE, e);
		}
	}

	private static int intOf(JsonObject o, String key, int fallback) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
	}
}
