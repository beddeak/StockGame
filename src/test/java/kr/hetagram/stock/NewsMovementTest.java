package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class NewsMovementTest {
    @TempDir Path directory;
    private GameConfig config;
    private static final long ANCHOR=Market.cents("10000000");
    private static final Random FIXED=new Random(1) { @Override public double nextDouble() {return 0;} };

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)) {
                Files.copy(Objects.requireNonNull(in),directory.resolve(name));
            }
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("config.yml").toFile());
        yaml.set("game.tick-seconds",4);yaml.set("game.volatility-reference-seconds",4);
        yaml.set("game.max-step-change-percent",8);yaml.set("news.max-instant-change-percent",25);
        yaml.set("news.max-total-change-percent",30);yaml.set("news.reversal-chance",0);
        // 엔진 공식 자체를 검증하므로 배포 프리셋(변동폭 0.5배, 즉시 변동 2배) 대신 1배를 씁니다.
        yaml.set("game.volatility-scale",1);yaml.set("news.instant-scale",1);
        yaml.save(directory.resolve("config.yml").toFile());
        var stocks=YamlConfiguration.loadConfiguration(directory.resolve("stocks.yml").toFile());
        stocks.set("stocks.redtech.min-change-percent",2);stocks.set("stocks.redtech.max-change-percent",2);
        stocks.set("stocks.redtech.base-rise-chance",1);stocks.save(directory.resolve("stocks.yml").toFile());
        config=GameConfig.load(directory.toFile());
    }

    private Market market(double seconds,double instant,double rise,double multiplier) {
        GameConfig timed;
        try{timed=config.withInterval(seconds);}catch(Exception e){throw new AssertionError(e);}
        var news=new GameConfig.News("test","{stock} 소식",List.of("redtech"),List.of(),1,600,rise,multiplier,instant);
        var market=new Market(new GameConfig(timed.rules(),timed.stocks(),List.of(news),timed.ui(),timed.map()));
        market.running=true;market.setPrice("redtech",ANCHOR);
        return market;
    }

    private void ticks(Market market,int count) {
        for(int i=0;i<count;i++) {
            market.clockMillis+=GameConfig.intervalMillis(market.config.rules().tickSeconds());
            market.tick(FIXED);
        }
    }
    /** 구현과 같은 BigDecimal 반올림으로 기대 가격을 계산합니다. */
    private static long times(long price,double factor) {
        return BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(factor)).setScale(0,RoundingMode.HALF_UP).longValueExact();
    }
    private static long logStep(long price,double logPercent) {return times(price,Math.exp(logPercent/100));}

    @Test void equalElapsedTimeHasEqualNewsGrowthAtFourSecondAndHalfSecondIntervals() {
        var slow=market(4,0,1,3);var fast=market(.5,0,1,3);
        slow.news("test","redtech",FIXED);fast.news("test","redtech",FIXED);
        ticks(slow,1);ticks(fast,8);
        // 4초 기준 2%×3배 추세는 로그 6%입니다. 0.5초 8번도 같은 추세를 나눠 적용합니다.
        assertEquals(logStep(ANCHOR,6),slow.quote("redtech").price);
        assertTrue(Math.abs(slow.quote("redtech").price-fast.quote("redtech").price)<=8,
                "갱신 횟수에 따른 차이는 매 단계 1전 이내의 반올림 오차뿐이어야 합니다");
        assertEquals(slow.clockMillis,fast.clockMillis);
    }

    @Test void zeroSecondSettingUsesFiftyMillisecondsWithoutMultiplyingNewsEightyTimes() {
        var slow=market(4,0,1,3);var fastest=market(0,0,1,3);
        slow.news("test","redtech",FIXED);fastest.news("test","redtech",FIXED);
        ticks(slow,1);ticks(fastest,80);
        assertEquals(4000,fastest.clockMillis);
        assertTrue(Math.abs(slow.quote("redtech").price-fastest.quote("redtech").price)<=80);
        assertNotNull(fastest.quote("redtech").effect);
    }

    @Test void positiveAndNegativeImmediatePercentagesApplyExactlyOnceAtAnnouncement() {
        for(int sign:List.of(1,-1)) {
            var market=market(.5,sign*8,sign>0?1:0,0);
            market.news("test","redtech",FIXED);
            // 호재 +8%는 ×1.08, 악재 -8%는 ÷1.08이라 같은 크기의 호재·악재가 서로 상쇄됩니다.
            long expected=sign>0?ANCHOR*108/100:times(ANCHOR,1/1.08);
            assertEquals(expected,market.quote("redtech").price);
            assertEquals(ANCHOR,market.quote("redtech").effect.anchor());
            ticks(market,10);
            assertEquals(expected,market.quote("redtech").price);
        }
    }

    @Test void totalNewsBudgetIncludesInstantMovementAndEndsAtEitherBoundaryThenNormalTradingResumes() {
        var good=market(4,25,1,20);good.news("test","redtech",FIXED);ticks(good,1);
        var positive=good.quote("redtech");
        assertEquals(ANCHOR*130/100,positive.price);assertEquals(positive.price,positive.history.getLast().high());
        assertNull(positive.effect);
        ticks(good,1);
        assertEquals(logStep(ANCHOR*130/100,2),positive.price);

        // 악재 하한은 기준가÷1.3입니다. +30% 뒤 같은 크기의 악재면 정확히 제자리입니다.
        var bad=market(4,-25,0,20);bad.news("test","redtech",FIXED);ticks(bad,1);
        var negative=bad.quote("redtech");
        long lower=BigDecimal.valueOf(ANCHOR).divide(BigDecimal.valueOf(1.3),0,RoundingMode.CEILING).longValueExact();
        assertEquals(lower,negative.price);assertEquals(negative.price,negative.history.getLast().low());
        assertNull(negative.effect);
        ticks(bad,1);
        assertEquals(logStep(lower,2),negative.price);

        // 상한은 내림, 하한은 올림하여 1전이라도 설정한 30% 범위를 넘지 않습니다.
        var oddGood=market(4,25,1,20);oddGood.setPrice("redtech",30002);
        oddGood.news("test","redtech",FIXED);ticks(oddGood,1);
        assertEquals(39002,oddGood.quote("redtech").price);
        var oddBad=market(4,-25,0,20);oddBad.setPrice("redtech",30002);
        oddBad.news("test","redtech",FIXED);ticks(oddBad,1);
        assertEquals(23079,oddBad.quote("redtech").price);

        // 상장폐지 가격에 닿는 하락은 뉴스 하한이 막지 않습니다.
        var nearDelisting=market(4,-25,0,20);nearDelisting.setPrice("redtech",13000);
        nearDelisting.news("test","redtech",FIXED);ticks(nearDelisting,1);
        assertTrue(nearDelisting.quote("redtech").delisted);
        assertEquals(0,nearDelisting.quote("redtech").price);
        assertNull(nearDelisting.quote("redtech").effect);
    }

    @Test void extremeNewsAndLongIntervalsAreCappedBeforeTheyCanExplodeOrProduceInfinity() {
        var instant=market(4,1000,1,20);instant.news("test","redtech",FIXED);
        assertEquals(ANCHOR*125/100,instant.quote("redtech").price);
        ticks(instant,1);assertEquals(ANCHOR*130/100,instant.quote("redtech").price);

        var step=market(4,0,1,20);step.news("test","redtech",FIXED);ticks(step,1);
        assertEquals(logStep(ANCHOR,8),step.quote("redtech").price);

        var longInterval=market(86400,0,1,20);
        longInterval.quote("redtech").effect=new Market.Effect(1,20,Long.MAX_VALUE);
        ticks(longInterval,1);
        assertEquals(logStep(ANCHOR,8),longInterval.quote("redtech").price);
    }

    @Test void manualPricesClearOldNewsBandsAndZeroTotalBudgetRemovesAllNewsPriceEffects() {
        var market=market(4,5,1,20);market.news("test","redtech",FIXED);
        assertNotNull(market.quote("redtech").effect);
        market.setPrice("redtech",ANCHOR*2);
        assertNull(market.quote("redtech").effect);
        ticks(market,1);assertEquals(logStep(ANCHOR*2,2),market.quote("redtech").price);
        long before=market.quote("redtech").price;
        market.config.ui().set("news.max-total-change-percent",0);
        market.news("test","redtech",FIXED);
        assertEquals(before,market.quote("redtech").price);assertNull(market.quote("redtech").effect);
    }
}
