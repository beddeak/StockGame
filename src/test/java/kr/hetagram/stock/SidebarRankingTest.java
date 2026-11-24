package kr.hetagram.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SidebarRankingTest {
    @TempDir Path directory;
    private GameConfig config;
    private Market market;

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(in),directory.resolve(name));
            }
        config=GameConfig.load(directory.toFile());
        config.ui().set("ui.sidebar-ranking-enabled",true);
        config.ui().set("ui.sidebar-ranking-limit",5);
        config.ui().set("messages.sidebar-ranking-row","&f{rank}. {player} &c{money}");
        config.ui().set("messages.sidebar-ranking-empty","&7참가자 없음");
        market=new Market(config);market.running=true;
    }

    private Market.Account player(String name,String cash) {
        var account=market.account(UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        account.name=name;account.cash=Market.cents(cash);return account;
    }

    @Test void sidebarListsEveryAccountByDefaultNetEquityRankingAndConfiguredLimit() {
        // 기본 map.yml을 그대로 사용합니다. 잘못된 접두사 경로를 테스트 설정으로 가리지 않습니다.
        assertEquals("net-equity",config.map().getString("ranking.metric"));
        assertFalse(config.map().contains("map.ranking.metric"));
        var second=player("Bob","1000");
        player("Alice","2000");player("Carol","10.25");
        config.ui().set("ui.sidebar-ranking-limit",2);

        assertEquals(List.of("&f1. Alice &c2,000","&f2. Bob &c1,000"),TradingUi.sidebarRankingLines(market,config));

        second.cash=Market.cents("3000");
        assertEquals(List.of("&f1. Bob &c3,000","&f2. Alice &c2,000"),TradingUi.sidebarRankingLines(market,config));
    }

    @Test void sidebarUsesSameEquityMetricAsWorldRankingWithoutReplacingCashBalances() {
        var buyer=UUID.nameUUIDFromBytes("Buyer".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var investor=market.account(buyer);investor.name="Buyer";investor.cash=Market.cents("1000000");
        player("Saver","1000000");
        market.setPrice("redtech",Market.cents("10000"));
        market.buy(buyer,"redtech",5);
        market.setPrice("redtech",Market.cents("30000"));

        config.map().set("ranking.metric","cash");
        assertEquals("&f1. Saver &c1,000,000",TradingUi.sidebarRankingLines(market,config).getFirst());
        config.map().set("ranking.metric","equity");
        assertEquals("&f1. Buyer &c1,100,000",TradingUi.sidebarRankingLines(market,config).getFirst());
        assertEquals(Market.cents("950000"),investor.cash);
    }

    @Test void emptyRankingsHaveConfiguredMessageAndCanBeDisabled() {
        assertEquals(List.of("&7참가자 없음"),TradingUi.sidebarRankingLines(market,config));
        config.ui().set("ui.sidebar-ranking-enabled",false);
        assertTrue(TradingUi.sidebarRankingLines(market,config).isEmpty());
    }

    @Test void rankingsReserveFiveRowsWhilePersonalMetricsAndHoldingsRemainIndependent() {
        var personal=rows("account",6);var ranking=rows("rank",5);var holdings=rows("holding",7);
        var initial=TradingUi.sidebarLines(personal,ranking,holdings,0);
        var rotated=TradingUi.sidebarLines(personal,ranking,holdings,3);

        assertEquals(15,initial.size());assertEquals(personal,initial.subList(0,6));
        assertEquals(ranking,initial.subList(6,11));assertEquals(ranking,rotated.subList(6,11));
        assertEquals(List.of("holding0","holding1","holding2","holding3"),initial.subList(11,15));
        assertEquals(List.of("holding4","holding5","holding6","holding0"),rotated.subList(11,15));
        var other=TradingUi.sidebarLines(rows("other-account",6),ranking,rows("other-holding",2),0);
        assertEquals(ranking,other.subList(6,11));
        assertEquals(List.of("other-holding0","other-holding1"),other.subList(11,13));
    }

    @Test void sidebarHandlesFullOrDisabledRankingsWithinMinecraftFifteenRows() {
        var noRanking=TradingUi.sidebarLines(rows("account",6),List.of(),rows("holding",10),0);
        assertEquals(15,noRanking.size());assertEquals("holding8",noRanking.getLast());
        var crowded=TradingUi.sidebarLines(rows("account",10),rows("rank",15),rows("holding",48),3);
        assertEquals(15,crowded.size());assertEquals(rows("rank",5),crowded.subList(10,15));
        assertEquals(rows("account",15),TradingUi.sidebarLines(rows("account",20),List.of("rank"),List.of("holding"),3));
        assertEquals(List.of(),TradingUi.sidebarLines(List.of(),List.of(),List.of(),3));
    }

    private static List<String> rows(String prefix,int count) {
        return IntStream.range(0,count).mapToObj(i->prefix+i).toList();
    }
}
