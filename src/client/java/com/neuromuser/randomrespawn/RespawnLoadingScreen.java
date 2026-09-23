package com.neuromuser.randomrespawn;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public class RespawnLoadingScreen extends Screen {
    private String currentKey = "randomrespawn.please_wait";
    private int progress = 0;
    private int ticksOpen = 0;
    private static final int MAX_TICKS_BEFORE_AUTO_CLOSE = 600;

    public RespawnLoadingScreen() {
        super(Text.translatable("randomrespawn.please_wait"));
    }

    public void updateProgress(String key, int progress) {
        this.currentKey = key;
        this.progress = progress;
        this.ticksOpen = 0;
    }

    @Override
    public void tick() {
        super.tick();
        ticksOpen++;

        if (ticksOpen >= MAX_TICKS_BEFORE_AUTO_CLOSE) {
            if (client != null) {
                client.setScreen(null);
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        Text statusText = Text.translatable(currentKey, progress);
        context.drawCenteredTextWithShadow(this.textRenderer, statusText, this.width / 2, this.height / 2, 0xFFFFFF);
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackgroundTexture(context, MENU_BACKGROUND_TEXTURE, 0, 0, 0.0F, 0.0F, this.width, this.height);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
