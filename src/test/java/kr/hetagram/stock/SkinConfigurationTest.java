package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SkinConfigurationTest {
    @TempDir Path directory;
    private GameConfig config;

    @BeforeEach void setup() throws Exception {
        for (String file : List.of("config.yml", "stocks.yml", "news.yml", "map.yml")) restore(file);
        config=GameConfig.load(directory.toFile());
    }

    private void restore(String file) throws Exception {
        try (var input=getClass().getResourceAsStream("/"+file)) {
            Files.copy(Objects.requireNonNull(input),directory.resolve(file),StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private GameConfig edit(String file,Consumer<YamlConfiguration> change) throws Exception {
        var yaml=new YamlConfiguration();yaml.load(directory.resolve(file).toFile());
        change.accept(yaml);yaml.save(directory.resolve(file).toFile());
        return GameConfig.load(directory.toFile());
    }

    @Test void skinCopyChangesOnlyTheSelectedCompanyAndPreservesAllOtherConfiguration() throws Exception {
        byte[] stocksBefore=Files.readAllBytes(directory.resolve("stocks.yml"));
        GameConfig updated=config.withSkin("blueenergy","Player_42");
        var previous=config.stocks().get("blueenergy");
        var current=updated.stocks().get("blueenergy");

        assertNotSame(config,updated);assertNotSame(config.stocks(),updated.stocks());
        assertEquals(config.rules(),updated.rules());assertEquals(config.news(),updated.news());
        assertEquals(config.ui().saveToString(),updated.ui().saveToString());assertEquals(config.map().saveToString(),updated.map().saveToString());
        assertEquals(config.loanRules(),updated.loanRules());
        assertEquals(List.copyOf(config.stocks().keySet()),List.copyOf(updated.stocks().keySet()));
        for (String id : config.stocks().keySet()) if(!id.equals("blueenergy"))
            assertEquals(config.stocks().get(id),updated.stocks().get(id),id);
        assertEquals("Player_42",current.skinName());assertEquals("MHF_Blaze",previous.skinName());
        assertEquals(previous.id(),current.id());assertEquals(previous.name(),current.name());
        assertEquals(previous.commandAliases(),current.commandAliases());assertEquals(previous.sector(),current.sector());
        assertEquals(previous.icon(),current.icon());assertEquals(previous.pedestal(),current.pedestal());
        assertEquals(previous.displayBlock(),current.displayBlock());assertEquals(previous.start(),current.start());
        assertEquals(previous.delist(),current.delist());assertEquals(previous.minChange(),current.minChange());
        assertEquals(previous.maxChange(),current.maxChange());assertEquals(previous.riseChance(),current.riseChance());
        assertEquals(config.stockCommandNames(),updated.stockCommandNames());
        assertEquals("blueenergy",updated.resolveStock("은고리궤도운송"));
        assertArrayEquals(stocksBefore,Files.readAllBytes(directory.resolve("stocks.yml")),"Copying config must not write operating settings");
    }

    @Test void applyingSkinConfigurationAndSavingDoesNotResetCashHoldingsQuotesOrNews() throws Exception {
        var market=new Market(config);market.running=true;
        UUID player=UUID.randomUUID();market.buy(player,"blueenergy",3);
        market.news("shield-up-01","deepoil",new java.util.Random(2));
        market.clockMillis=1234;market.latestHeadline="이전 뉴스 보존";
        long cash=market.account(player).cash,cost=market.account(player).holdings.get("blueenergy").cost;
        long price=market.quote("blueenergy").price;var history=List.copyOf(market.quote("deepoil").history);
        var effect=market.quote("deepoil").effect;

        var updated=config.withSkin("blueenergy","Player_42");market.reconfigure(updated);
        var store=new StateStore(directory.toFile());store.save(market);
        var restored=new Market(updated);store.load(restored);

        assertTrue(restored.running);assertEquals(1234,restored.clockMillis);
        assertEquals("이전 뉴스 보존",restored.latestHeadline);
        assertEquals(cash,restored.account(player).cash);
        assertEquals(3,restored.account(player).holdings.get("blueenergy").quantity);
        assertEquals(cost,restored.account(player).holdings.get("blueenergy").cost);
        assertEquals(price,restored.quote("blueenergy").price);
        assertEquals(history,List.copyOf(restored.quote("deepoil").history));
        assertEquals(effect,restored.quote("deepoil").effect);
        assertEquals("Player_42",restored.quote("blueenergy").spec.skinName());
    }

    @Test void configuredSkinReloadsWithCompanyNamesAliasesAndDefaultPricesIntact() throws Exception {
        var reloaded=edit("stocks.yml",yaml->yaml.set("stocks.deepoil.skin-name","Trader_123"));
        assertEquals("Trader_123",reloaded.stocks().get("deepoil").skinName());
        assertEquals(config.stocks().get("deepoil").name(),reloaded.stocks().get("deepoil").name());
        assertEquals(config.stockCommandNames(),reloaded.stockCommandNames());
        assertEquals("deepoil",reloaded.resolveStock("현무방호"));
        assertEquals(config.stocks().get("deepoil").start(),reloaded.stocks().get("deepoil").start());
        assertEquals(config.rules(),reloaded.rules());assertEquals(config.news(),reloaded.news());
    }

    @Test void nicknameLengthLimitsAndBlankDefaultSkinAreAccepted() {
        assertEquals("a_1",config.withSkin("redtech","a_1").stocks().get("redtech").skinName());
        assertEquals("1234567890ABCDEF",config.withSkin("redtech","1234567890ABCDEF").stocks().get("redtech").skinName());
        var cleared=config.withSkin("redtech","Player_42").withSkin("redtech","");
        assertEquals("",cleared.stocks().get("redtech").skinName());
        assertEquals(config.withSkin("redtech","").stocks().get("redtech"),cleared.stocks().get("redtech"));
    }

    @Test void invalidNicknameCopiesFailWithoutChangingTheOriginalCompanyOrFiles() throws Exception {
        byte[] before=Files.readAllBytes(directory.resolve("stocks.yml"));
        for (String nickname : List.of("a","ab","a".repeat(17),"Player-42","한글"," Player_42","Player 42","\t"))
            assertThrows(IllegalArgumentException.class,()->config.withSkin("redtech",nickname),nickname);
        assertThrows(IllegalArgumentException.class,()->config.withSkin("redtech",null));
        assertThrows(IllegalArgumentException.class,()->config.withSkin("unknown-company","Player_42"));
        assertEquals("MHF_Melon",config.stocks().get("redtech").skinName());
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("stocks.yml")));
    }

    @Test void invalidNicknamesInYamlAreRejectedRatherThanSilentlyChangingTheSkin() throws Exception {
        for (String nickname : List.of("a","ab","a".repeat(17),"Player-42","Player 42"," ")) {
            restore("stocks.yml");
            assertThrows(IllegalArgumentException.class,
                    ()->edit("stocks.yml",yaml->yaml.set("stocks.cloud.skin-name",nickname)),nickname);
        }
    }

    @Test void invalidNpcDisplayModeAndPositionSettingsAreRejected() throws Exception {
        for (Object invalid : List.of("villager","",true,123)) {
            restore("map.yml");
            assertThrows(IllegalArgumentException.class,
                    ()->edit("map.yml",yaml->yaml.set("kiosk.display-mode",invalid)),String.valueOf(invalid));
        }
        for (Object invalid : List.of(-0.01,8.01,Double.NaN,Double.POSITIVE_INFINITY,"invalid")) {
            restore("map.yml");
            assertThrows(IllegalArgumentException.class,
                    ()->edit("map.yml",yaml->yaml.set("kiosk.npc-height",invalid)),String.valueOf(invalid));
        }
        for (Object invalid : List.of(-360.01,360.01,Double.NaN,Double.NEGATIVE_INFINITY,"invalid")) {
            restore("map.yml");
            assertThrows(IllegalArgumentException.class,
                    ()->edit("map.yml",yaml->yaml.set("kiosk.npc-yaw",invalid)),String.valueOf(invalid));
        }
    }

    @Test void oldMapsDefaultToPlayerTradersAndLoadingDoesNotRewriteTheMapFile() throws Exception {
        edit("map.yml",yaml->{
            yaml.set("kiosk.display-mode",null);yaml.set("kiosk.npc-height",null);yaml.set("kiosk.npc-yaw",null);
        });
        byte[] before=Files.readAllBytes(directory.resolve("map.yml"));
        var restored=GameConfig.load(directory.toFile());
        assertEquals("player",restored.map().getString("kiosk.display-mode"));
        assertEquals(1.0,restored.map().getDouble("kiosk.npc-height"));
        assertEquals(0.0,restored.map().getDouble("kiosk.npc-yaw"));
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("map.yml")));
    }

    @Test void oldStocksWithoutSkinNamesKeepTheirIdentitiesAndUseTheDefaultSkin() throws Exception {
        var restored=edit("stocks.yml",yaml->{
            for (String id : config.stocks().keySet()) yaml.set("stocks."+id+".skin-name",null);
        });
        for (String id : config.stocks().keySet())
            assertEquals(config.withSkin(id,"").stocks().get(id),restored.stocks().get(id));
        assertTrue(restored.stocks().values().stream().allMatch(stock->stock.skinName().isEmpty()));
        var block=edit("map.yml",yaml->{
            yaml.set("kiosk.display-mode","block");yaml.set("kiosk.npc-height",8);yaml.set("kiosk.npc-yaw",-360);
        });
        assertEquals("block",block.map().getString("kiosk.display-mode"));
        assertEquals(8,block.map().getDouble("kiosk.npc-height"));
        assertEquals(-360,block.map().getDouble("kiosk.npc-yaw"));
    }
}
