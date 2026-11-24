package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TerminalQuantityConfigurationTest {
    @TempDir Path directory;
    private Path file;
    private byte[] original;

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var input=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(input),directory.resolve(name));}
        file=directory.resolve("config.yml");original=Files.readAllBytes(file);
    }
    private YamlConfiguration originalYaml() throws Exception {
        var yaml=new YamlConfiguration();yaml.loadFromString(new String(original,java.nio.charset.StandardCharsets.UTF_8));return yaml;
    }

    @Test void shippedControlsTradeOneShareNormallyAndTenSharesWhileSneakingOnBothSides() throws Exception {
        var config=GameConfig.load(directory.toFile());
        assertEquals(1,config.terminalQuantity(true,false));assertEquals(1,config.terminalQuantity(false,false));
        assertEquals(10,config.terminalQuantity(true,true));assertEquals(10,config.terminalQuantity(false,true));
        assertArrayEquals(original,Files.readAllBytes(file));
    }

    @Test void missingLegacySneakKeysAreFilledIndividuallyWithoutChangingNormalQuantitiesOrTheFile() throws Exception {
        for(Set<String> missing:List.of(Set.of("buy","sell"),Set.of("buy"),Set.of("sell"))) {
            var yaml=originalYaml();yaml.set("game.click-buy-quantity",3);yaml.set("game.click-sell-quantity",7);
            yaml.set("game.sneak-click-buy-quantity",17);yaml.set("game.sneak-click-sell-quantity",19);
            for(String side:missing)yaml.set("game.sneak-click-"+side+"-quantity",null);
            yaml.save(file.toFile());byte[] legacy=Files.readAllBytes(file);var config=GameConfig.load(directory.toFile());
            assertEquals(3,config.terminalQuantity(true,false));assertEquals(7,config.terminalQuantity(false,false));
            assertEquals(missing.contains("buy")?10:17,config.terminalQuantity(true,true));
            assertEquals(missing.contains("sell")?10:19,config.terminalQuantity(false,true));
            assertArrayEquals(legacy,Files.readAllBytes(file));
        }
    }

    @Test void allFourQuantitiesAreIndependentAndTheSneakMaximumIsAccepted() throws Exception {
        var yaml=originalYaml();yaml.set("game.click-buy-quantity",4);yaml.set("game.click-sell-quantity",6);
        yaml.set("game.sneak-click-buy-quantity",40);yaml.set("game.sneak-click-sell-quantity",60);yaml.save(file.toFile());
        var config=GameConfig.load(directory.toFile());
        assertEquals(4,config.terminalQuantity(true,false));assertEquals(6,config.terminalQuantity(false,false));
        assertEquals(40,config.terminalQuantity(true,true));assertEquals(60,config.terminalQuantity(false,true));
        yaml.set("game.sneak-click-buy-quantity",1000000);yaml.set("game.sneak-click-sell-quantity",1);yaml.save(file.toFile());
        config=GameConfig.load(directory.toFile());assertEquals(1000000,config.terminalQuantity(true,true));assertEquals(1,config.terminalQuantity(false,true));
        assertEquals(4,config.terminalQuantity(true,false));assertEquals(6,config.terminalQuantity(false,false));
    }

    @Test void invalidSneakQuantitiesAreRejectedWithoutRepairingOrOverwritingTheOriginalFile() throws Exception {
        for(String key:List.of("game.sneak-click-buy-quantity","game.sneak-click-sell-quantity"))
            for(Object invalid:List.of(0,-1,"10",1.5,1000001,true,List.of(10),Double.NaN)) {
                var yaml=originalYaml();yaml.set(key,invalid);yaml.save(file.toFile());byte[] before=Files.readAllBytes(file);
                assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()),key+"="+invalid);
                assertArrayEquals(before,Files.readAllBytes(file));
            }
    }
}
