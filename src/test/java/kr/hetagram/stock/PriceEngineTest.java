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

/** "악재가 안 떨어진다"·"시간이 갈수록 다 내려간다"·"갱신 간격을 바꾸면 출렁임이 달라진다" 회귀를 막습니다. */
class PriceEngineTest {
    @TempDir Path directory;
    private GameConfig config;
    private static final long ANCHOR=Market.cents("10000000");

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        yaml.set("game.volatility-scale",1);yaml.set("news.instant-scale",1);yaml.set("news.reversal-chance",0);
        yaml.set("game.volatility-reference-seconds",4);yaml.set("game.max-step-change-percent",8);
        yaml.save(directory.resolve("config.yml").toFile());
        var stocks=YamlConfiguration.loadConfiguration(directory.resolve("stocks.yml").toFile());
        stocks.set("stocks.redtech.min-change-percent",1);stocks.set("stocks.redtech.max-change-percent",3);
        stocks.set("stocks.redtech.base-rise-chance",.5);stocks.save(directory.resolve("stocks.yml").toFile());
        config=GameConfig.load(directory.toFile());
    }
    private Market market(double seconds,List<GameConfig.News> news) throws Exception {
        var timed=config.withInterval(seconds);
        // 측정 대상 종목만 두어 0.05초 간격 반복도 빠르게 끝냅니다.
        var only=new java.util.LinkedHashMap<String,GameConfig.Stock>();only.put("redtech",timed.stocks().get("redtech"));
        var market=new Market(new GameConfig(timed.rules(),only,news,timed.ui(),timed.map()));
        market.running=true;market.setPrice("redtech",ANCHOR);return market;
    }

    /** 같은 40초 동안의 로그 수익률 표준편차·평균을 측정합니다. 시드가 고정되어 매번 같은 값입니다. */
    private double[] fortySecondLogReturns(double seconds,long seed) throws Exception {
        var market=market(seconds,List.of());var random=new Random(seed);
        long step=GameConfig.intervalMillis(seconds);int ticks=(int)(40000/step);double sum=0,squares=0;int samples=seconds<.5?1500:3000;
        for(int sample=0;sample<samples;sample++) {
            market.setPrice("redtech",ANCHOR);
            for(int i=0;i<ticks;i++){market.clockMillis+=step;market.tick(random);}
            double r=Math.log((double)market.quote("redtech").price/ANCHOR);sum+=r;squares+=r*r;
        }
        double mean=sum/samples;return new double[]{Math.sqrt(squares/samples-mean*mean),mean};
    }

    @Test void volatilityPerMinuteDoesNotDependOnTheAdminTickIntervalAndHasNoDownwardDrift() throws Exception {
        double[] reference=fortySecondLogReturns(4,1),fast=fortySecondLogReturns(.5,2),slow=fortySecondLogReturns(10,3),fastest=fortySecondLogReturns(0,4);
        for(double[] other:List.of(fast,slow,fastest)) {
            assertEquals(1,other[0]/reference[0],.1,"같은 시간 동안의 흔들림은 갱신 간격과 무관해야 합니다");
            assertTrue(Math.abs(other[1])<other[0]*.1,"상승확률 0.5에서 로그 수익률이 한쪽으로 쏠리지 않아야 합니다");
        }
        assertTrue(Math.abs(reference[1])<reference[0]*.1);
    }

    @Test void sameSizedGoodAndBadNewsCancelExactlyInsteadOfLeavingTheStockLower() throws Exception {
        var up=new GameConfig.News("up","{stock} 호재",List.of("redtech"),List.of(),1,600,.5,0,8);
        var down=new GameConfig.News("down","{stock} 악재",List.of("redtech"),List.of(),1,600,.5,0,-8);
        var market=market(4,List.of(up,down));var fixed=new Random(1);
        market.news("up","redtech",fixed);market.news("down","redtech",fixed);
        assertEquals(ANCHOR,market.quote("redtech").price,1,"+8% 호재 뒤 -8% 악재는 제자리여야 합니다");
    }

    @Test void displayedChangeComparesWithThePriceOneWindowAgoInsteadOfTheLastTick() throws Exception {
        var market=market(1,config.news());var quote=market.quote("redtech");
        // 상장폐지가(100원)보다 충분히 높은 가격으로 측정합니다.
        market.clockMillis=0;market.setPrice("redtech",Market.cents("10000"));
        market.clockMillis=30000;market.setPrice("redtech",Market.cents("15000"));
        market.clockMillis=61000;market.setPrice("redtech",Market.cents("20000"));
        assertEquals(100,quote.change(),1e-9,"61초 시점은 60초 전인 0초의 1만 원과 비교합니다");
        market.clockMillis=95000;market.setPrice("redtech",Market.cents("22000"));
        assertEquals(100.0*(22000-15000)/15000,quote.change(),1e-9,"95초 시점은 35초 이전 마지막 가격인 1만5천 원과 비교합니다");
        market.config.ui().set("ui.change-window-seconds",0);
        market.clockMillis=96000;market.setPrice("redtech",Market.cents("11000"));
        assertEquals(-50,quote.change(),1e-9,"0이면 예전처럼 직전 가격과 비교합니다");
    }
}
