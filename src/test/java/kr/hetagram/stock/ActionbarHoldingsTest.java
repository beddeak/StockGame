package kr.hetagram.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ActionbarHoldingsTest {
    @TempDir Path directory;
    private GameConfig config;

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(in),directory.resolve(name));
            }
        config=GameConfig.load(directory.toFile());
    }

    @Test void rotatesBaseAndEachHoldingInConfiguredServerTickIntervals() {
        config.ui().set("ui.actionbar-holdings-enabled",true);
        config.ui().set("ui.actionbar-page-ticks",60);
        var holdings=List.of("새벽제분 3주","현무방호 1주","은고리궤도운송 5주");
        assertEquals("자산",TradingUi.actionbarLine(config,"자산",holdings,0));
        assertEquals("자산",TradingUi.actionbarLine(config,"자산",holdings,59));
        assertEquals(holdings.get(0),TradingUi.actionbarLine(config,"자산",holdings,60));
        assertEquals(holdings.get(0),TradingUi.actionbarLine(config,"자산",holdings,119));
        assertEquals(holdings.get(1),TradingUi.actionbarLine(config,"자산",holdings,120));
        assertEquals(holdings.get(2),TradingUi.actionbarLine(config,"자산",holdings,180));
        assertEquals("자산",TradingUi.actionbarLine(config,"자산",holdings,240));
        config.ui().set("ui.actionbar-page-ticks",20);
        assertEquals(holdings.get(0),TradingUi.actionbarLine(config,"자산",holdings,20));
    }

    @Test void noHoldingsAlwaysShowsTheBaseAccountPage() {
        for(long ticks:List.of(0L,60L,120L,Long.MAX_VALUE))
            assertEquals("현금 1,000,000",TradingUi.actionbarLine(config,"현금 1,000,000",List.of(),ticks));
    }

    @Test void holdingDisplayCanBeDisabledWithoutReplacingTheBasePage() {
        config.ui().set("ui.actionbar-holdings-enabled",false);
        assertEquals("자산",TradingUi.actionbarLine(config,"자산",List.of("보유종목"),60));
        config.ui().set("ui.actionbar-holdings-enabled",true);
        assertEquals("보유종목",TradingUi.actionbarLine(config,"자산",List.of("보유종목"),60));
    }

    @Test void holdingsAreHiddenInSidebarAndShortNamesAreConfiguredForTheActionbar() {
        assertFalse(config.ui().getBoolean("ui.sidebar-holdings-enabled"));
        assertTrue(config.ui().getBoolean("ui.actionbar-holdings-enabled"));
        assertEquals(60,config.ui().getInt("ui.actionbar-page-ticks"));
        assertTrue(config.msg("actionbar-holding").contains("{shortStock}"));
        assertFalse(config.msg("actionbar-holding").contains("{stock}"));
        assertEquals("현무방호",config.stockCommandName("deepoil"));
        assertTrue(config.stocks().get("deepoil").name().length()>config.stockCommandName("deepoil").length());
    }
}
