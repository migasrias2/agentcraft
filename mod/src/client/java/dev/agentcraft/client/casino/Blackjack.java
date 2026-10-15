package dev.agentcraft.client.casino;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Blackjack rules and state, with no Minecraft code in it (the GUI is {@link BlackjackScreen}).
 *
 * <ul>
 * <li>Six-deck shoe, reshuffled before a deal once fewer than 25% of the cards are left.
 * <li>Aces count 11 until that would bust ("soft" hands). The dealer hits 16 and below (soft 16
 * included) and stands on every 17, soft ones too.
 * <li>Blackjack (two cards, not after a split) pays 3:2; a normal win pays 1:1; a push returns the bet.
 * <li>Hit, Stand, Double (first two cards of a hand), Split (once, two cards of equal value; split
 * aces get one card each). No insurance, no surrender. The dealer peeks, so a dealer blackjack ends
 * the round at once.
 * </ul>
 *
 * Chips are deducted when they go on the table and paid back at settlement, so {@link #balance()} is
 * always what is in the player's pocket.
 */
public final class Blackjack {
	public static final int DECKS = 6;
	public static final int MIN_BET = 10;
	public static final int START_BALANCE = 1000;

	public enum Suit {
		SPADES(false), HEARTS(true), DIAMONDS(true), CLUBS(false);

		private final boolean red;

		Suit(boolean red) {
			this.red = red;
		}

		public boolean red() {
			return red;
		}
	}

	/** rank 1 = ace, 2..10, 11 = jack, 12 = queen, 13 = king. */
	public record Card(int rank, Suit suit) {
		public int pips() {
			return Math.min(rank, 10);
		}

		public String label() {
			return switch (rank) {
				case 1 -> "A";
				case 11 -> "J";
				case 12 -> "Q";
				case 13 -> "K";
				default -> Integer.toString(rank);
			};
		}
	}

	public enum Phase { BETTING, PLAYER, DEALER, DONE }

	public enum Outcome { PENDING, BLACKJACK, WIN, PUSH, LOSE }

	/** Running totals, persisted by {@link BlackjackSave}. */
	public static final class Stats {
		public int hands;
		public int won;
		public int lost;
		public int biggestWin;
	}

	/** One player hand (there are two after a split). */
	public static final class Hand {
		private final List<Card> cards = new ArrayList<>();
		private int bet;
		private boolean doubled;
		private boolean fromSplit;
		private boolean finished;
		private Outcome outcome = Outcome.PENDING;
		private int returned;

		private Hand(int bet, boolean fromSplit) {
			this.bet = bet;
			this.fromSplit = fromSplit;
		}

		public List<Card> cards() {
			return Collections.unmodifiableList(cards);
		}

		public int bet() {
			return bet;
		}

		public boolean doubled() {
			return doubled;
		}

		public boolean finished() {
			return finished;
		}

		public Outcome outcome() {
			return outcome;
		}

		/** Chips paid back at settlement (0 for a loss); the net result is {@code returned - bet}. */
		public int returned() {
			return returned;
		}

		public int value() {
			return Blackjack.value(cards);
		}

		public boolean soft() {
			return Blackjack.soft(cards);
		}

		public boolean busted() {
			return value() > 21;
		}

		public boolean blackjack() {
			return !fromSplit && cards.size() == 2 && value() == 21;
		}
	}

	/** The card shoe. {@link #stacked} builds a fixed one for tests. */
	public static final class Shoe {
		private final List<Card> cards = new ArrayList<>();
		private final Random random;
		private final boolean fixed;
		private int next;
		private int total;

		public Shoe(Random random) {
			this.random = random;
			this.fixed = false;
			shuffle();
		}

		private Shoe(List<Card> top) {
			this.random = new Random(0);
			this.fixed = true;
			cards.addAll(top);
			total = cards.size();
		}

		/** A shoe that deals exactly these cards, in order, and never reshuffles on its own (tests). */
		public static Shoe stacked(Card... top) {
			return new Shoe(List.of(top));
		}

		public void shuffle() {
			cards.clear();
			for (int d = 0; d < DECKS; d++) {
				for (Suit s : Suit.values()) {
					for (int r = 1; r <= 13; r++) {
						cards.add(new Card(r, s));
					}
				}
			}
			Collections.shuffle(cards, random);
			next = 0;
			total = cards.size();
		}

		public Card draw() {
			if (next >= cards.size()) {
				if (fixed) {
					throw new IllegalStateException("stacked shoe is empty");
				}
				shuffle();
			}
			return cards.get(next++);
		}

		public int remaining() {
			return cards.size() - next;
		}

		public boolean needsShuffle() {
			return !fixed && remaining() * 4 < total;
		}
	}

	private final Shoe shoe;
	private final Stats stats;
	private int balance;
	private int bet;
	private Phase phase = Phase.BETTING;
	private final List<Hand> hands = new ArrayList<>();
	private final List<Card> dealer = new ArrayList<>();
	private int active;
	private boolean holeRevealed;
	private int lastNet;
	private int lastBet;
	private int round;
	private boolean shuffledNow;
	private String says = "Place your bets.";

	public Blackjack(Shoe shoe, int balance, Stats stats) {
		this.shoe = shoe;
		this.balance = Math.max(0, balance);
		this.stats = stats;
	}

	// ------------------------------------------------------------------ hand values

	/** Best total of a hand: aces are 11 while that does not bust. */
	public static int value(List<Card> cards) {
		int sum = 0;
		boolean ace = false;
		for (Card c : cards) {
			sum += c.pips();
			ace |= c.rank() == 1;
		}
		return ace && sum + 10 <= 21 ? sum + 10 : sum;
	}

	/** True when an ace is currently counted as 11. */
	public static boolean soft(List<Card> cards) {
		int sum = 0;
		boolean ace = false;
		for (Card c : cards) {
			sum += c.pips();
			ace |= c.rank() == 1;
		}
		return ace && sum + 10 <= 21;
	}

	// ------------------------------------------------------------------ read access

	public int balance() {
		return balance;
	}

	public int bet() {
		return bet;
	}

	public Phase phase() {
		return phase;
	}

	public List<Hand> hands() {
		return Collections.unmodifiableList(hands);
	}

	public int activeIndex() {
		return active;
	}

	public List<Card> dealerCards() {
		return Collections.unmodifiableList(dealer);
	}

	public boolean holeRevealed() {
		return holeRevealed;
	}

	/** The dealer's total as the player sees it: only the up card until the hole card is turned. */
	public int dealerShownValue() {
		if (dealer.isEmpty()) {
			return 0;
		}
		return holeRevealed ? value(dealer) : value(dealer.subList(0, 1));
	}

	public boolean dealerBusted() {
		return holeRevealed && value(dealer) > 21;
	}

	/** Counts the deals: a new value means a new round (the table view uses it to clear the old cards). */
	public int round() {
		return round;
	}

	public int lastNet() {
		return lastNet;
	}

	public boolean shuffledNow() {
		return shuffledNow;
	}

	public String says() {
		return says;
	}

	public int cardsLeft() {
		return shoe.remaining();
	}

	public Stats stats() {
		return stats;
	}

	/** What to persist: the balance, with the chips of a round in progress counted as still in the pocket. */
	public int savedBalance() {
		int inPlay = 0;
		if (phase == Phase.PLAYER || phase == Phase.DEALER) {
			for (Hand h : hands) {
				inPlay += h.bet;
			}
		}
		return balance + inPlay;
	}

	/** Largest bet the balance allows, in steps of {@link #MIN_BET}. */
	public int maxBet() {
		return balance / MIN_BET * MIN_BET;
	}

	// ------------------------------------------------------------------ betting

	public boolean canBet() {
		return phase == Phase.BETTING;
	}

	/** Adds chips to the bet (capped by the balance). Returns false when not possible. */
	public boolean addBet(int chips) {
		if (!canBet() || chips <= 0 || bet >= maxBet()) {
			return false;
		}
		bet = Math.min(bet + chips, maxBet());
		return true;
	}

	/** Takes chips off the bet. */
	public boolean subBet(int chips) {
		if (!canBet() || chips <= 0 || bet == 0) {
			return false;
		}
		bet = Math.max(0, bet - chips);
		return true;
	}

	public boolean clearBet() {
		if (!canBet() || bet == 0) {
			return false;
		}
		bet = 0;
		return true;
	}

	public boolean canDeal() {
		return phase == Phase.BETTING && bet >= MIN_BET && bet <= balance;
	}

	public boolean canRebuy() {
		return (phase == Phase.BETTING || phase == Phase.DONE) && balance < MIN_BET;
	}

	public boolean rebuy() {
		if (!canRebuy()) {
			return false;
		}
		balance += START_BALANCE;
		says = "A fresh stack. Good luck.";
		return true;
	}

	/** Back to betting after a finished round; the last bet stays on the table if the balance allows. */
	public boolean nextRound() {
		if (phase != Phase.DONE) {
			return false;
		}
		hands.clear();
		dealer.clear();
		holeRevealed = false;
		active = 0;
		lastNet = 0;
		shuffledNow = false;
		bet = Math.min(lastBet, maxBet());
		phase = Phase.BETTING;
		says = "Place your bets.";
		return true;
	}

	// ------------------------------------------------------------------ the round

	public boolean deal() {
		if (!canDeal()) {
			return false;
		}
		shuffledNow = shoe.needsShuffle();
		if (shuffledNow) {
			shoe.shuffle();
		}
		round++;
		balance -= bet;
		lastBet = bet;
		lastNet = 0;
		hands.clear();
		dealer.clear();
		holeRevealed = false;
		Hand h = new Hand(bet, false);
		hands.add(h);
		h.cards.add(shoe.draw());
		dealer.add(shoe.draw());
		h.cards.add(shoe.draw());
		dealer.add(shoe.draw());
		active = 0;
		phase = Phase.PLAYER;
		says = shuffledNow ? "A fresh shoe. Good luck." : "Good luck.";
		if (h.blackjack() || value(dealer) == 21) {
			// the dealer peeks: either blackjack ends the round on the spot
			for (Hand x : hands) {
				x.finished = true;
			}
			holeRevealed = true;
			settle();
		}
		return true;
	}

	private Hand current() {
		return hands.get(active);
	}

	public boolean canAct() {
		return phase == Phase.PLAYER && active < hands.size() && !current().finished;
	}

	public boolean canHit() {
		return canAct();
	}

	public boolean canStand() {
		return canAct();
	}

	public boolean canDouble() {
		return canAct() && current().cards.size() == 2 && balance >= current().bet;
	}

	public boolean canSplit() {
		if (!canAct() || hands.size() != 1) {
			return false;
		}
		Hand h = current();
		return h.cards.size() == 2 && h.cards.get(0).pips() == h.cards.get(1).pips() && balance >= h.bet;
	}

	public boolean hit() {
		if (!canHit()) {
			return false;
		}
		Hand h = current();
		h.cards.add(shoe.draw());
		if (h.value() >= 21) {
			says = h.busted() ? "Bust." : "Twenty-one.";
			finishHand();
		} else {
			says = "";
		}
		return true;
	}

	public boolean stand() {
		if (!canStand()) {
			return false;
		}
		says = "";
		finishHand();
		return true;
	}

	public boolean doubleDown() {
		if (!canDouble()) {
			return false;
		}
		Hand h = current();
		balance -= h.bet;
		h.bet *= 2;
		h.doubled = true;
		h.cards.add(shoe.draw());
		says = h.busted() ? "Bust." : "Double down.";
		finishHand();
		return true;
	}

	public boolean split() {
		if (!canSplit()) {
			return false;
		}
		Hand first = current();
		Hand second = new Hand(first.bet, true);
		first.fromSplit = true;
		balance -= first.bet;
		second.cards.add(first.cards.remove(1));
		first.cards.add(shoe.draw());
		second.cards.add(shoe.draw());
		hands.add(second);
		says = "Split.";
		if (first.cards.get(0).rank() == 1) {
			// split aces take one card each and stand
			first.finished = true;
			second.finished = true;
			active = hands.size();
			afterPlayer();
		} else {
			autoFinishTwentyOne();
		}
		return true;
	}

	/** Ends the active hand and moves to the next one (or to the dealer). */
	private void finishHand() {
		current().finished = true;
		active++;
		autoFinishTwentyOne();
	}

	private void autoFinishTwentyOne() {
		while (active < hands.size() && !current().finished && current().value() == 21) {
			current().finished = true;
			active++;
		}
		if (active >= hands.size()) {
			afterPlayer();
		}
	}

	private void afterPlayer() {
		phase = Phase.DEALER;
	}

	/** True while the dealer still has to draw: below 17, soft 16 and under included; every 17 stands. */
	public boolean dealerMustHit() {
		boolean live = false;
		for (Hand h : hands) {
			live |= !h.busted();
		}
		return live && value(dealer) < 17;
	}

	/**
	 * One step of the dealer's turn, for the GUI to pace: first turns the hole card, then one draw per
	 * call, and finally settles the round. Returns false when it is not the dealer's turn.
	 */
	public boolean dealerStep() {
		if (phase != Phase.DEALER) {
			return false;
		}
		if (!holeRevealed) {
			holeRevealed = true;
			says = "";
		} else if (dealerMustHit()) {
			dealer.add(shoe.draw());
		} else {
			settle();
		}
		return true;
	}

	/** Plays the dealer's whole turn at once (tests, or skipping the animation). */
	public void finishDealer() {
		while (phase == Phase.DEALER) {
			dealerStep();
		}
	}

	private void settle() {
		int dv = value(dealer);
		boolean dealerBj = dealer.size() == 2 && dv == 21;
		int paid = 0;
		int wagered = 0;
		for (Hand h : hands) {
			wagered += h.bet;
			if (h.busted()) {
				h.outcome = Outcome.LOSE;
				h.returned = 0;
			} else if (h.blackjack()) {
				h.outcome = dealerBj ? Outcome.PUSH : Outcome.BLACKJACK;
				h.returned = dealerBj ? h.bet : h.bet + h.bet * 3 / 2;
			} else if (dealerBj) {
				h.outcome = Outcome.LOSE;
				h.returned = 0;
			} else if (dv > 21 || h.value() > dv) {
				h.outcome = Outcome.WIN;
				h.returned = h.bet * 2;
			} else if (h.value() == dv) {
				h.outcome = Outcome.PUSH;
				h.returned = h.bet;
			} else {
				h.outcome = Outcome.LOSE;
				h.returned = 0;
			}
			paid += h.returned;
			stats.hands++;
			if (h.returned > h.bet) {
				stats.won++;
				stats.biggestWin = Math.max(stats.biggestWin, h.returned - h.bet);
			} else if (h.returned < h.bet) {
				stats.lost++;
			}
		}
		balance += paid;
		lastNet = paid - wagered;
		holeRevealed = true;
		phase = Phase.DONE;
		says = dealerBj ? "Dealer has blackjack." : dv > 21 ? "Dealer busts." : "Dealer has " + dv + ".";
	}

	/** A short result line for the banner: "Blackjack! +150", "Dealer busts +100", "Push", "Bust -50". */
	public String banner() {
		if (phase != Phase.DONE || hands.isEmpty()) {
			return "";
		}
		if (hands.size() == 1) {
			return bannerFor(hands.get(0));
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < hands.size(); i++) {
			sb.append(i == 0 ? "" : "  |  ").append(bannerFor(hands.get(i)));
		}
		return sb.toString();
	}

	private String bannerFor(Hand h) {
		int net = h.returned - h.bet;
		String amount = net > 0 ? " +" + net : net < 0 ? " -" + -net : "";
		return switch (h.outcome) {
			case BLACKJACK -> "Blackjack!" + amount;
			case WIN -> (dealerBusted() ? "Dealer busts" : "You win") + amount;
			case PUSH -> "Push";
			case LOSE -> (h.busted() ? "Bust" : value(dealer) == 21 && dealer.size() == 2 ? "Dealer blackjack" : "Dealer wins") + amount;
			case PENDING -> "";
		};
	}
}
