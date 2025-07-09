package org.craftllc.minecraft.mod.cycm.config; // Правильний пакет

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.text.Text;
import org.craftllc.minecraft.mod.cycm.client.CYCMClient; // Оновлений імпорт до головного клієнтського класу

public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getConfigScreenFactory() {
        return parent -> new ModConfigScreen(parent);
    }

    private static class ModConfigScreen extends Screen {
        private final Screen parent;
        private CheckboxWidget modEnabledCheckbox;

        protected ModConfigScreen(Screen parent) {
            super(Text.literal("Конфігурація CraftLLC YouTube Command Mod [CYCM]"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            super.init();

            modEnabledCheckbox = CheckboxWidget.builder(Text.literal("Увімкнути обробку"), this.textRenderer)
                    .pos(this.width / 2 - 75, this.height / 2 - 20)
                    .checked(CYCMClient.config.isModEnabled())
                    .callback((checkbox, checked) -> {
                        CYCMClient.setModEnabled(checked);
                    })
                    .build();
            this.addDrawableChild(modEnabledCheckbox);

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), (button) -> {
                this.client.setScreen(this.parent);
            }).bounds(this.width / 2 - 75, this.height / 2 + 20, 150, 20).build());
        }

        @Override
        public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
            this.renderBackground(graphics);
            super.render(graphics, mouseX, mouseY, delta);
            graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
        }
    }
}