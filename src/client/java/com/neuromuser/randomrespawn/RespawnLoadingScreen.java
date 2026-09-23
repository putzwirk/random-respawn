package com.neuromuser.randomrespawn;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
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
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        super.render(matrices, mouseX, mouseY, delta);

        Text statusText = Text.translatable(currentKey, progress);
        drawCenteredTextWithShadow(matrices, this.textRenderer, statusText.asOrderedText(), this.width / 2, this.height / 2, 0xFFFFFF);
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
