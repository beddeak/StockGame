package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class NewsStateCompatibilityTest {
    @TempDir Path directory;
    private GameConfig config;
    private StateStore store;

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(in),directory.resolve(name));
            }
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        yaml.set("game.tick-seconds",4);yaml.set("game.volatility-reference-seconds",4);
        yaml.set("game.max-step-change-percent",8);yaml.set("news.max-instant-change-percent",25);
        yaml.set("news.max-total-change-percent",30);yaml.set("news.reversal-chance",0);
        yaml.save(directory.resolve("config.yml").toFile());
        config=GameConfig.load(directory.toFile());store=new StateStore(directory.toFile());
    }

    @Test void legacyEffectsStartTheirBudgetAtSavedCurrentPriceWithoutWritingDuringLoad() throws Exception {
        var original=new Market(config);original.running=true;original.clockMillis=2000;original.nextNewsMillis=5000;
        original.setPrice("redtech",Market.cents("123456.78"));
        original.quote("redtech").effect=new Market.Effect(.9,3,60000);
        store.save(original);
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("state.yml").toFile());
        yaml.set("quotes.redtech.effect.anchor",null);yaml.set("quotes.redtech.effect.max-change",null);
        yaml.save(directory.resolve("state.yml").toFile());
        byte[] legacyBytes=Files.readAllBytes(directory.resolve("state.yml"));

        var restored=new Market(config);store.load(restored);

        var effect=restored.quote("redtech").effect;
        assertNotNull(effect);assertEquals(restored.quote("redtech").price,effect.anchor());
        assertEquals(30,effect.maxChange());assertEquals(.9,effect.rise());assertEquals(3,effect.multiplier());assertEquals(60000,effect.expires());
        assertEquals(2000,restored.clockMillis);assertEquals(5000,restored.nextNewsMillis);assertTrue(restored.running);
        assertArrayEquals(legacyBytes,Files.readAllBytes(directory.resolve("state.yml")));
    }

    @Test void explicitlySavedThreeArgumentManualEffectsKeepTheirUnlimitedCompatibilityMode() throws Exception {
        var original=new Market(config);original.setPrice("redtech",Market.cents("123456.78"));
        var manual=new Market.Effect(.75,2.5,90000);original.quote("redtech").effect=manual;
        store.save(original);
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("state.yml").toFile());
        assertTrue(yaml.isSet("quotes.redtech.effect.anchor"));assertEquals(0,yaml.getLong("quotes.redtech.effect.anchor"));
        assertTrue(yaml.isSet("quotes.redtech.effect.max-change"));assertEquals(0,yaml.getDouble("quotes.redtech.effect.max-change"));

        var restored=new Market(config);store.load(restored);

        assertEquals(manual,restored.quote("redtech").effect);
        assertEquals(0,restored.quote("redtech").effect.anchor());assertEquals(0,restored.quote("redtech").effect.maxChange());
    }

    @Test void restartingPreservesTheOriginalAnnouncementAnchorAndRemainingNewsBudget() throws Exception {
        var news=new GameConfig.News("test","{stock} 호재",List.of("redtech"),List.of(),1,600,1,20,25);
        var configured=new GameConfig(config.rules(),config.stocks(),List.of(news),config.ui(),config.map());
        var original=new Market(configured);original.running=true;
        long anchor=Market.cents("100000");original.setPrice("redtech",anchor);
        original.news("test","redtech",new Random(1));
        assertEquals(anchor*125/100,original.quote("redtech").price);
        store.save(original);

        var restored=new Market(configured);store.load(restored);

        assertEquals(anchor,restored.quote("redtech").effect.anchor());
        assertEquals(original.quote("redtech").effect,restored.quote("redtech").effect);
        restored.clockMillis+=4000;
        restored.tick(new Random(1) { @Override public double nextDouble() {return 0;} });
        assertEquals(anchor*130/100,restored.quote("redtech").price);
        assertNull(restored.quote("redtech").effect);
        store.save(restored);
        var ended=new Market(configured);store.load(ended);
        assertEquals(anchor*130/100,ended.quote("redtech").price);assertNull(ended.quote("redtech").effect);
    }
}
