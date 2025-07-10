package org.craftllc.minecraft.mod.cycm; // Пакет змінено на клієнтський

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.text.Text;
import org.craftllc.minecraft.mod.cycm.config.ModConfig; // Правильний імпорт конфігурації
import net.minecraft.client.gui.GuiGraphics; // Додано імпорт для GuiGraphics

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

            // Переконайтеся, що ModConfig.load() викликається до того, як ви звертаєтеся до CYCMClient.config.
            // Можливо, краще передавати ModConfig.config через конструктор,
            // або мати статичний метод для отримання поточної конфігурації.
            // Наразі залишаємо так, як було, але звертаємо увагу на цей момент.

            modEnabledCheckbox = CheckboxWidget.builder(Text.literal("Увімкнути обробку"), this.textRenderer)
                    .pos(this.width / 2 - 75, this.height / 2 - 20)
                    .checked(ModConfig.load().isModEnabled()) // Завантажуємо конфігурацію напряму
                    .callback((checkbox, checked) -> {
                        ModConfig config = ModConfig.load();
                        config.setModEnabled(checked);
                        ModConfig.save(config);
                        CYCMClient.setModEnabled(checked); // Викликаємо метод з CYCMClient
                    })
                    .build();
            this.addDrawableChild(modEnabledCheckbox);

            this.addDrawableChild(ButtonWidget.builder(Text.literal("Готово"), (button) -> {
                this.client.setScreen(this.parent);
            }).bounds(this.width / 2 - 75, this.height / 2 + 20, 150, 20).build());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) { // Змінено net.minecraft.client.gui.GuiGraphics на GuiGraphics
            this.renderBackground(graphics);
            super.render(graphics, mouseX, mouseY, delta);
            graphics.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFF);
        }
    }
}
