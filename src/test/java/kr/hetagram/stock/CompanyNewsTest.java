package kr.hetagram.stock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CompanyNewsTest {
    @TempDir Path directory;
    private GameConfig config;
    private static final Map<String,String> SECTORS=Map.of("redtech","food","cloud","entertainment","deepoil","military","greenbio","ai","blueenergy","space");

    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
        config=GameConfig.load(directory.toFile());
    }

    @Test void eachStableStockIdHasItsRequestedBusinessSector() {
        assertEquals(SECTORS.keySet(),config.stocks().keySet());
        SECTORS.forEach((id,sector)->assertEquals(sector,config.stocks().get(id).sector(),id));
    }

    @Test void everyCompanyHasFivePositiveAndFiveNegativeNewsItemsExplicitlyTargetedAtIt() {
        assertEquals(50,config.news().size());
        for(String stock:SECTORS.keySet()) {
            var companyNews=config.news().stream().filter(n->n.stocks().equals(List.of(stock))).toList();
            assertEquals(10,companyNews.size(),stock);
            assertEquals(5,companyNews.stream().filter(n->n.immediate()>0).count(),stock);
            assertEquals(5,companyNews.stream().filter(n->n.immediate()<0).count(),stock);
            for(var news:companyNews) {
                assertTrue(news.sectors().isEmpty(),news.id());assertTrue(news.headline().contains("{stock}"),news.id());
            }
        }
        assertFalse(config.news().stream().anyMatch(n->List.of("oil-discovery","clinical-success","cockroach","recall","investigation","hit-product").contains(n.id())));
    }

    @Test void defaultCompanyNewsUsesStrongAnnouncementAndSustainedEffectsWithBiggerRises() {
        for(var news:config.news()) {
            assertEquals(1,news.weight(),news.id());
            assertTrue(news.duration()>=35&&news.duration()<=45,news.id());
            assertTrue(news.multiplier()>=1.8&&news.multiplier()<=3,news.id());
            assertTrue(Math.abs(news.immediate())>=4&&Math.abs(news.immediate())<=9,news.id());
            if(news.immediate()>0)assertTrue(news.riseChance()>=.82&&news.riseChance()<=.9,news.id());
            else assertTrue(news.riseChance()>=.12&&news.riseChance()<=.2,news.id());
        }
        // 도파민 프리셋: 회사마다 호재의 발표 변동·지속 배수 합이 악재보다 커서 떡상이 떡락보다 크게 보입니다.
        for(String stock:SECTORS.keySet()) {
            var companyNews=config.news().stream().filter(n->n.stocks().equals(List.of(stock))).toList();
            assertTrue(companyNews.stream().filter(n->n.immediate()>0).mapToDouble(GameConfig.News::immediate).sum()
                    >-companyNews.stream().filter(n->n.immediate()<0).mapToDouble(GameConfig.News::immediate).sum(),stock);
            assertTrue(companyNews.stream().filter(n->n.immediate()>0).mapToDouble(GameConfig.News::multiplier).sum()
                    >companyNews.stream().filter(n->n.immediate()<0).mapToDouble(GameConfig.News::multiplier).sum(),stock);
        }
        assertEquals(.7,config.ui().getDouble("game.volatility-scale"));assertEquals(2.5,config.ui().getDouble("news.instant-scale"));
        assertEquals(45,config.ui().getDouble("news.max-total-change-percent"));assertEquals(25,config.ui().getDouble("news.max-instant-change-percent"));
    }

    @Test void forcedCompanyNewsRejectsEveryOtherCompanyAndAcceptsItsExplicitTarget() {
        for(var news:config.news()) {
            var market=new Market(config);market.running=true;String target=news.stocks().getFirst();
            for(String stock:SECTORS.keySet())if(!stock.equals(target)) {
                long before=market.quote(stock).price;
                assertThrows(IllegalArgumentException.class,()->market.news(news.id(),stock,new Random(1)),news.id()+" -> "+stock);
                assertEquals(before,market.quote(stock).price);
            }
            assertEquals(target,market.news(news.id(),target,new Random(1)).stockId(),news.id());
        }
    }
}
