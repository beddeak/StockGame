package kr.hetagram.stock;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RecordingUpgradeTest {
    @TempDir Path directory;
    private static final Map<Material,Long> PRICES=Map.ofEntries(
            Map.entry(Material.COAL,300L),Map.entry(Material.RAW_COPPER,200L),Map.entry(Material.RAW_IRON,600L),
            Map.entry(Material.RAW_GOLD,1000L),Map.entry(Material.REDSTONE,100L),Map.entry(Material.LAPIS_LAZULI,150L),
            Map.entry(Material.DIAMOND,2500L),Map.entry(Material.EMERALD,3000L),Map.entry(Material.COD,4000L),
            Map.entry(Material.SALMON,5000L),Map.entry(Material.TROPICAL_FISH,7000L),Map.entry(Material.PUFFERFISH,6000L));
    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml","work.yml"))
            try(var stream=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(stream),directory.resolve(name));}
    }
    private YamlConfiguration read(String name) throws Exception {var yaml=new YamlConfiguration();yaml.load(directory.resolve(name).toFile());return yaml;}
    private void save(String name,YamlConfiguration yaml) throws Exception {yaml.save(directory.resolve(name).toFile());}
    private void pending() throws Exception {
        // 운영 경로나 실행 서버 파일을 읽지 않는 독립 fixture입니다. 가격은 비율이 아닌 명시적인 최종 원 단위 값입니다.
        var yaml=new YamlConfiguration();yaml.set("schema",1);yaml.set("game.broadcast-trades",false);
        yaml.set("loans.block-transfers-while-indebted",true);yaml.set("ranking.metric","net-equity");yaml.set("work.regeneration-ticks",600);
        PRICES.forEach((material,won)->yaml.set("work.catalog."+material.name(),won));save("pending-recording-upgrade.yml",yaml);
    }
    @Test void repeatedLoadsInEitherOrderApplyExplicitPricesOnceAndPreserveEveryInputAndPendingManifest() throws Exception {
        var config=read("config.yml");config.set("game.broadcast-trades",true);config.set("loans.block-transfers-while-indebted",false);config.set("game.tick-seconds",2.75);config.set("game.initial-cash",1234567);save("config.yml",config);
        var map=read("map.yml");map.set("ranking.metric","cash");save("map.yml",map);
        var work=read("work.yml");work.set("quarry.regeneration-ticks",40);PRICES.forEach((material,won)->work.set("catalog."+material.name(),won*100));save("work.yml",work);
        pending();Files.writeString(directory.resolve("state.yml"),"accounts: preserve-without-loading\n");
        var before=new LinkedHashMap<String,byte[]>();for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml","work.yml","state.yml","pending-recording-upgrade.yml"))before.put(name,Files.readAllBytes(directory.resolve(name)));
        for(int i=0;i<4;i++) {
            WorkSettings settings;GameConfig game;
            if(i%2==0){game=GameConfig.load(directory.toFile());settings=WorkSettings.load(directory.toFile());}
            else {settings=WorkSettings.load(directory.toFile());game=GameConfig.load(directory.toFile());}
            assertFalse(game.rules().broadcastTrades());assertTrue(game.ui().getBoolean("loans.block-transfers-while-indebted"));assertEquals("net-equity",game.map().getString("ranking.metric"));
            assertEquals(2.75,game.rules().tickSeconds());assertEquals(123456700L,game.rules().initialCash());assertEquals(600,settings.quarry().regenerationTicks());assertEquals(12,settings.catalog().size());
            PRICES.forEach((material,won)->assertEquals(won*100,settings.catalog().get(material),material.name()));
        }
        for(var entry:before.entrySet())assertArrayEquals(entry.getValue(),Files.readAllBytes(directory.resolve(entry.getKey())),entry.getKey());
    }
    @Test void oldDefaultQuantityListGetsHundredShareButtonButCustomQuantitiesRemainUntouched() throws Exception {
        pending();var config=read("config.yml");config.set("ui.buy-quantities",List.of(1,10));save("config.yml",config);
        assertEquals(List.of(1,10,100),GameConfig.load(directory.toFile()).rules().quantities());
        assertEquals(List.of(1,10),read("config.yml").getIntegerList("ui.buy-quantities"));
        for(var custom:List.of(List.of(2,20),List.of(1,10,50),List.of(100))) {
            config.set("ui.buy-quantities",custom);save("config.yml",config);assertEquals(custom,GameConfig.load(directory.toFile()).rules().quantities());
        }
    }
    @Test void bundledEconomyUsesVanillaExpectedDropsWithMiningBelowThousandWonAfterTheSaleBuff() throws Exception {
        var settings=WorkSettings.load(directory.toFile());assertTrue(settings.yaml().getBoolean("round-resources-only"));assertEquals(600,settings.quarry().regenerationTicks());
        var oldMiningWon=Map.of(Material.COAL,3000L,Material.RAW_COPPER,2000L,Material.RAW_IRON,6000L,Material.RAW_GOLD,10000L,Material.REDSTONE,1000L,Material.LAPIS_LAZULI,1500L,Material.DIAMOND,25000L,Material.EMERALD,30000L);
        var oldFishWon=Map.of(Material.COD,20000L,Material.SALMON,25000L,Material.TROPICAL_FISH,35000L,Material.PUFFERFISH,30000L);
        // 녹화 전 가격 인하(광물 1/10, 생선 1/5) 뒤 판매가를 1.5배로 올렸습니다.
        oldMiningWon.forEach((material,won)->assertEquals(won*100/10*3/2,settings.catalog().get(material),material.name()));
        oldFishWon.forEach((material,won)->assertEquals(won*100/5*3/2,settings.catalog().get(material),material.name()));
        var drops=Map.of(Material.COAL_ORE,Map.entry(Material.COAL,1.0),Material.COPPER_ORE,Map.entry(Material.RAW_COPPER,3.5),Material.IRON_ORE,Map.entry(Material.RAW_IRON,1.0),Material.GOLD_ORE,Map.entry(Material.RAW_GOLD,1.0),Material.REDSTONE_ORE,Map.entry(Material.REDSTONE,4.5),Material.LAPIS_ORE,Map.entry(Material.LAPIS_LAZULI,6.5),Material.DIAMOND_ORE,Map.entry(Material.DIAMOND,1.0),Material.EMERALD_ORE,Map.entry(Material.EMERALD,1.0));
        double expectedCents=0;int total=0;
        for(var ore:settings.quarry().ores()){var drop=drops.get(ore.material());assertNotNull(drop);expectedCents+=ore.weight()*drop.getValue()*settings.catalog().get(drop.getKey());total+=ore.weight();}
        double wonPerBlock=expectedCents/total/100.0;assertEquals(967.875,wonPerBlock,.0001);assertTrue(wonPerBlock<=1000);
    }
    @Test void persistenceConfigurationRejectsOutOfRangeAndFractionalOptionsBeforeOpeningTheMarket() throws Exception {
        var valid=GameConfig.load(directory.toFile());var options=StatePersistence.Options.from(valid.ui());assertEquals(20,options.flushIntervalTicks());assertEquals(4,options.retryAttempts());assertEquals(75,options.retryDelayMillis());assertEquals(300,options.backupIntervalSeconds());assertEquals(12,options.backupRetention());
        var invalid=Map.of("flush-interval-ticks",List.of(19,1201,20.5),"retry-attempts",List.of(0,11,1.5),"retry-delay-millis",List.of(-1,5001,1.5),"backup-interval-seconds",List.of(-1,86401,1.5),"backup-retention",List.of(0,1001,1.5));
        for(var entry:invalid.entrySet())for(Number value:entry.getValue()) {
            var yaml=read("config.yml");yaml.set("persistence."+entry.getKey(),value);save("config.yml",yaml);byte[] before=Files.readAllBytes(directory.resolve("config.yml"));
            assertThrows(IllegalArgumentException.class,()->GameConfig.load(directory.toFile()),entry.getKey()+"="+value);assertArrayEquals(before,Files.readAllBytes(directory.resolve("config.yml")));
            save("config.yml",valid.ui());
        }
    }
}
