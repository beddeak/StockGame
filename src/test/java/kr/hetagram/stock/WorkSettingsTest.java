package kr.hetagram.stock;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkSettingsTest {
    @TempDir Path directory;
    private YamlConfiguration source() throws Exception {
        var yaml=new YamlConfiguration();
        try(var stream=WorkSettings.class.getResourceAsStream("/work.yml")) {
            assertNotNull(stream);yaml.loadFromString(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
        return yaml;
    }
    @Test void missingOperatingFileUsesBundledDefaultsWithoutCreatingDirectory() throws Exception {
        var absent=directory.resolve("not-created");var settings=WorkSettings.load(absent.toFile());
        assertFalse(Files.exists(absent));assertTrue(settings.enabled());assertEquals(7,settings.pond().size());assertEquals(3,settings.pond().depth());
        assertTrue(settings.kit().containsKey(Material.IRON_PICKAXE));assertTrue(settings.kit().containsKey(Material.FISHING_ROD));
        for(Material fish:List.of(Material.COD,Material.SALMON,Material.TROPICAL_FISH,Material.PUFFERFISH))assertTrue(settings.catalog().get(fish)>0);
    }
    @Test void legacyShopGetsOnlyParkourButtonDefaultsWithoutLosingEditedPricesOrSlots() throws Exception {
        var yaml=source();
        for(String key:List.of("slot","icon","name"))yaml.set("shop.parkour-"+key,null);
        yaml.set("catalog.COAL",1234);yaml.set("shop.mine-slot",50);
        Path file=directory.resolve("work.yml");yaml.save(file.toFile());byte[] before=Files.readAllBytes(file);
        var loaded=WorkSettings.load(directory.toFile());
        assertEquals(48,loaded.yaml().getInt("shop.parkour-slot"));assertEquals("FEATHER",loaded.yaml().getString("shop.parkour-icon"));
        assertEquals(Market.cents("1234"),loaded.catalog().get(Material.COAL));assertEquals(50,loaded.yaml().getInt("shop.mine-slot"));
        assertArrayEquals(before,Files.readAllBytes(file));
        var duplicate=source();duplicate.set("shop.parkour-slot",duplicate.getInt("shop.sell-slot"));
        assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(duplicate));
    }
    @Test void yuanPricesPreserveCentsAndOperatingLoadNeverRewrites() throws Exception {
        var yaml=source();yaml.set("catalog.COAL","12.34");Files.writeString(directory.resolve("work.yml"),yaml.saveToString());
        byte[] before=Files.readAllBytes(directory.resolve("work.yml"));var settings=WorkSettings.load(directory.toFile());
        assertEquals(1234L,settings.catalog().get(Material.COAL));assertArrayEquals(before,Files.readAllBytes(directory.resolve("work.yml")));
        assertThrows(UnsupportedOperationException.class,()->settings.catalog().clear());assertThrows(UnsupportedOperationException.class,()->settings.kit().clear());
    }
    @Test void rejectsInvalidOreWeightsAndNonOreMaterials() throws Exception {
        for(String key:List.of("quarry.ores.COAL_ORE","quarry.regeneration-ticks","quarry.occupied-retry-ticks")) {
            var yaml=source();yaml.set(key,0);assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(yaml),key);
        }
        var yaml=source();yaml.set("quarry.ores.STONE",10);assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(yaml));
    }
    @Test void pondRequiresOpenWaterSizedOddSourceVolumeAndQuarryHasRoomForExit() throws Exception {
        for(var entry:Map.of("pond.size",6,"pond.depth",2,"quarry.depth",5,"quarry.size",4).entrySet()) {
            var yaml=source();yaml.set(entry.getKey(),entry.getValue());assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(yaml));
        }
        var yaml=source();yaml.set("quarry.size",5);yaml.set("quarry.depth",4);assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(yaml));
    }
    @Test void refusesSellableFreeKitAndOversizedShopCatalog() throws Exception {
        var yaml=source();yaml.set("catalog.IRON_PICKAXE",999);assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(yaml));
        var oversized=source();oversized.set("catalog",null);for(int i=0;i<46;i++)oversized.set("catalog.ITEM_"+i,1);
        assertTrue(assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(oversized)).getMessage().contains("45"));
    }
    @Test void refusesInvalidShopButtonsLoreAndUnbreakableFlag() throws Exception {
        var duplicate=source();duplicate.set("shop.close-slot",duplicate.getInt("shop.sell-slot"));assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(duplicate));
        var outside=source();outside.set("shop.mine-slot",0);assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(outside));
        var lore=source();lore.set("shop.item-lore",List.of("valid",123));assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(lore));
        var flag=source();flag.set("kit-unbreakable","yes please");assertThrows(IllegalArgumentException.class,()->WorkSettings.parse(flag));
    }
    @Test void reloadAllowsBalanceAndTextButRejectsLayoutOrEnableChanges() throws Exception {
        var original=WorkSettings.parse(source());var changed=source();changed.set("catalog.COAL",1234);changed.set("quarry.ores.COAL_ORE",1);
        changed.set("quarry.regeneration-ticks",400);changed.set("text.quarry","new text");
        assertDoesNotThrow(()->original.requireSameLayout(WorkSettings.parse(changed)));
        for(String key:List.of("enabled","quarry.enabled","pond.enabled")) {
            var disabled=source();disabled.set(key,false);assertThrows(IllegalArgumentException.class,()->original.requireSameLayout(WorkSettings.parse(disabled)));
        }
        var moved=source();moved.set("quarry.offset-x",60);assertThrows(IllegalArgumentException.class,()->original.requireSameLayout(WorkSettings.parse(moved)));
    }
    @Test void weightedSelectionHonorsBothEndsWithoutAddingUnlistedOres() throws Exception {
        var quarry=WorkSettings.parse(source()).quarry();
        Random first=new Random(){@Override public int nextInt(int bound){return 0;}};
        Random last=new Random(){@Override public int nextInt(int bound){return bound-1;}};
        assertEquals(quarry.ores().getFirst().material(),quarry.choose(first));assertEquals(quarry.ores().getLast().material(),quarry.choose(last));
        var allowed=quarry.ores().stream().map(WorkSettings.Ore::material).toList();var random=new Random(42);
        for(int i=0;i<1000;i++)assertTrue(allowed.contains(quarry.choose(random)));
    }
}
