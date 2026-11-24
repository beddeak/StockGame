package kr.hetagram.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TextFormattingTest {
    @TempDir Path directory;
    private GameConfig config;

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(in),directory.resolve(name));
            }
        config=GameConfig.load(directory.toFile());
    }

    @Test void defaultMoneyAndPercentageDisplaysHaveNoDecimalPart() {
        assertEquals(0,config.ui().getInt("ui.money-decimals"));
        assertEquals(0,config.ui().getInt("ui.percent-decimals"));
        assertEquals("123,457",Text.money(config,Market.cents("123456.78")));
        assertEquals("-1,235",Text.money(config,Market.cents("-1234.50")));
        assertEquals("0",Text.money(config,0));
        assertEquals("+13",Text.percent(config,12.5));
        assertEquals("-13",Text.percent(config,-12.5));
        assertEquals("+0",Text.percent(config,0));
        assertEquals("1,235",Text.money(Market.cents("1234.50")));
        assertEquals("+13",Text.percent(12.5));
    }

    @Test void precisionSettingsCanShowOneOrTwoDecimalPlacesAgain() {
        config.ui().set("ui.money-decimals",2);config.ui().set("ui.percent-decimals",1);
        assertEquals("123,456.78",Text.money(config,Market.cents("123456.78")));
        assertEquals("100.00",Text.money(config,Market.cents("100")));
        assertEquals("-12.3",Text.percent(config,-12.34));
        config.ui().set("ui.money-decimals",1);config.ui().set("ui.percent-decimals",2);
        assertEquals("123,456.8",Text.money(config,Market.cents("123456.78")));
        assertEquals("+12.35",Text.percent(config,12.345));
    }

    @Test void hidingFractionsDoesNotRoundActualTradesCashAverageOrRealizedProfit() {
        var market=new Market(config);market.running=true;
        UUID investor=UUID.randomUUID();
        market.setPrice("redtech",Market.cents("100000.57"));
        var purchase=market.buy(investor,"redtech",3);
        var account=market.account(investor);var position=account.holdings.get("redtech");

        assertEquals("300,002",Text.money(config,purchase.amount()));
        assertEquals("699,998",Text.money(config,account.cash));
        assertEquals("100,001",Text.money(config,position.average()));
        assertEquals(Market.cents("300001.71"),position.cost);
        assertEquals(Market.cents("699998.29"),account.cash);
        assertEquals(Market.cents("100000.57"),position.average());

        market.setPrice("redtech",Market.cents("100001.29"));market.sell(investor,"redtech",1);
        assertEquals("800,000",Text.money(config,account.cash));
        assertEquals("1",Text.money(config,account.realized));
        assertEquals(Market.cents("799999.58"),account.cash);
        assertEquals(Market.cents("0.72"),account.realized);
        assertEquals(Market.cents("200001.14"),position.cost);
    }

    @Test void nonFinitePercentageFormattingKeepsExistingDiagnosticOutput() {
        assertEquals("NaN",Text.percent(config,Double.NaN));
        assertEquals("+Infinity",Text.percent(config,Double.POSITIVE_INFINITY));
        assertEquals("-Infinity",Text.percent(config,Double.NEGATIVE_INFINITY));
    }
}
