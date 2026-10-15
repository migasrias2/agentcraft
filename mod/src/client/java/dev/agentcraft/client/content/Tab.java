package dev.agentcraft.client.content;

/** One tab of the Content Studio: draws into the body rectangle and registers its clicks on the canvas. */
interface Tab {
	void render(Canvas c, int x, int y, int w, int h);
}
