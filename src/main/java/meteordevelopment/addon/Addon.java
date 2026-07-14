package meteordevelopment.addon;

import meteordevelopment.addon.modules.EconomyGambler;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Addon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("EconomyGamblerAddon");
    public static final Category GAMBLING_CATEGORY = new Category("Gambling", Items.GOLD_INGOT.getDefaultStack());

    @Override
    public void onInitialize() {
        LOG.info("Initialisiere Economy Gambler Addon...");
        Modules.get().add(new EconomyGambler());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(GAMBLING_CATEGORY);
    }

    @Override
    public String getPackage() {
        return "meteordevelopment.addon";
    }
}
