package kr.hetagram.stock;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

/** 모든 개조 가능한 값은 YAML에서 읽습니다. 로드가 끝나기 전에는 실행 중 설정을 바꾸지 않습니다. */
public record GameConfig(Rules rules, LinkedHashMap<String, Stock> stocks, List<News> news,
                         YamlConfiguration ui, YamlConfiguration map) {
    // 주가 1경 원(전 단위 10^18)까지 설정 가능. 보유자별 자산 한도는 Market에서 별도로 보호합니다.
    public static final long MAX_PRICE_CENTS=1000000000000000000L;
    // 관리자 GUI 입력 하한. 1초 간격·5%면 20초 만에 이자 100% 상한에 닿아 게임이 성립하지 않습니다.
    static final int MIN_ADMIN_INTEREST_INTERVAL_SECONDS=10,MIN_ADMIN_TERM_SECONDS=30;
    public record Rules(long initialCash, boolean autoStart, double tickSeconds, int autosaveSeconds,
                        long maxPrice, long maxShares, double buyFee, double sellFee,
                        boolean tradeStopped, boolean teleportFirst, boolean broadcastTrades,
                        int newsMin, int newsMax, double reversal, int refreshTicks, List<Integer> quantities,
                        double maxTickSeconds, int chartRefreshTicks) {}
    public record Stock(String id, String name, String sector, Material icon, long start, long delist,
                        double minChange, double maxChange, double riseChance, Material pedestal, Material displayBlock,
                        List<String> commandAliases, String skinName) {
        public Stock {commandAliases=List.copyOf(commandAliases);}
    }
    /**
     * 만기 일시상환형 사채의 계약 조건. 선이자(upfrontFeePercent)를 떼고 지급하고, 이자는 간격마다 쌓였다가 만기에 한 번에 회수합니다.
     * 총이자 상한(maxInterestPercent)은 선이자를 포함하며 누적 차입 원금 기준입니다.
     */
    public record LoanRules(boolean enabled,long bankruptcyThreshold,long minPrincipal,long maxPrincipal,
                            long defaultPrincipal,int termSeconds,int interestIntervalSeconds,double interestPercent,
                            double maxInterestPercent,double upfrontFeePercent,boolean requireLowAssets,boolean allowTopUp) {}
    /** 단순화 이전 규칙의 설정 키입니다. 새 규칙과 모순되므로 업그레이드 때 지웁니다. */
    static final List<String> RETIRED_LOAN_KEYS=List.of("loans.late-fee-percent","loans.penalty-mode","loans.penalty-seconds",
            "loans.max-missed-payments","loans.bankruptcy-grace-seconds","loans.auto-liquidate-collateral",
            "messages.loan-overdue","messages.loan-penalty-ended","messages.loan-missed","messages.loan-interest-unpaid",
            "messages.debt-overdue","messages.loan-collateral-enabled","messages.loan-collateral-disabled");
    /** 사채 규칙을 설명하는 문구입니다. 업그레이드 때 새 규칙 기본 문구로 교체합니다. */
    static final List<String> LOAN_TEXT_KEYS=List.of("ui.lender.loan-lore","ui.lender.status-lore","ui.lender.borrow-name",
            "messages.loan-borrowed","messages.loan-repaid","messages.loan-status","messages.loan-collected","messages.loan-bankrupt",
            "messages.loan-cleared","messages.loan-collateral-sold","messages.loan-collateral-forfeited","messages.loan-no-cash",
            "messages.loan-setting-changed","messages.loan-due-soon","messages.loan-due-soon-title","messages.loan-due-soon-subtitle",
            "messages.loan-bankrupt-title","messages.loan-bankrupt-subtitle","messages.debt-active","messages.debt-bankrupt",
            "messages.collector-started","messages.collector-ended","messages.collector-death","messages.collector-seized",
            "messages.offline-loan-header","messages.offline-loan-line","messages.offline-kinds");
    public record News(String id, String headline, List<String> stocks, List<String> sectors,
                       double weight, int duration, double riseChance, double multiplier, double immediate) {
        public boolean matches(Stock stock) {
            return (stocks.isEmpty() || stocks.contains(stock.id())) &&
                   (sectors.isEmpty() || sectors.contains(stock.sector()));
        }
    }
    public static GameConfig load(File directory) throws Exception {
        return load(directory,null);
    }
    /** 단일 키 저장 후보도 실제 파일을 바꾸기 전에 나머지 설정과 함께 검증합니다. */
    private static YamlConfiguration bundledConfig() throws Exception {
        var defaults=new YamlConfiguration();
        try(var in=new java.io.InputStreamReader(Objects.requireNonNull(GameConfig.class.getResourceAsStream("/config.yml")),java.nio.charset.StandardCharsets.UTF_8)){defaults.load(in);}
        return defaults;
    }
    static GameConfig load(File directory,YamlConfiguration configOverride) throws Exception {
        YamlConfiguration c = configOverride==null?read(directory, "config.yml"):configOverride, s = read(directory, "stocks.yml"),
                n = read(directory, "news.yml"), m = read(directory, "map.yml");
        // 실행 중인 구버전 관리자 창이 config.yml을 다시 저장해 새 UI 키가 빠져도 시작할 수 있게 합니다.
        // 새 UI/뉴스 계산 항목만 번들 YAML에서 보충하며 금액·종목·기존 설정 오류는 그대로 검증합니다.
        var addedUiKeys=List.of("ui.money-decimals","ui.percent-decimals","ui.sidebar-holdings-enabled",
                "ui.actionbar-holdings-enabled","ui.actionbar-page-ticks","messages.actionbar-holding",
                "game.volatility-reference-seconds","game.max-step-change-percent",
                "news.max-instant-change-percent","news.max-total-change-percent","loans",
                "messages.skin-fetching","messages.skin-loaded","messages.skin-not-found","messages.skin-failed",
                "messages.loan-borrowed","messages.loan-repaid","messages.loan-status","messages.loan-none",
                "messages.loan-collected","messages.loan-due-soon",
                "messages.loan-bankrupt","messages.loan-cleared","messages.sidebar-debt","messages.pay-sent","messages.pay-received",
                "messages.debt-active","messages.debt-bankrupt","messages.debt-none",
                "loans.npc-name","loans.collector","loans.require-low-assets","loans.allow-top-up","ui.lender",
                "ui.lender.eligibility-open","ui.lender.eligibility-limited","ui.lender.admin-slot","ui.lender.admin-icon","ui.lender.admin-name","ui.loan-admin",
                "ui.work-shop-slot","ui.work-shop-icon","ui.work-shop-name",
                "messages.lender-spawned","messages.lender-removed","messages.lender-skin","messages.lender-none","messages.lender-location",
                "messages.collector-started","messages.collector-ended","messages.collector-death","messages.loan-setting-changed",
                "loans.collector.grace-seconds","loans.collector.rest-seconds","loans.upfront-fee-percent","loans.due-warning-seconds",
                "messages.loan-collateral-sold","messages.loan-collateral-forfeited","messages.collector-seized",
                "persistence","persistence.config-backup-retention","loans.block-transfers-while-indebted","messages.loan-no-cash","messages.offline-loan-header","messages.offline-loan-line",
                "messages.save-failed","messages.reloaded-with-rollback","messages.offline-kinds",
                "game.sneak-click-buy-quantity","game.sneak-click-sell-quantity",
                "messages.offline-kinds.DUE_SOON","messages.loan-due-soon-title","messages.loan-due-soon-subtitle",
                "messages.loan-bankrupt-title","messages.loan-bankrupt-subtitle",
                "game.volatility-scale","news.instant-scale","ui.change-window-seconds");
        if(addedUiKeys.stream().anyMatch(key->!c.isSet(key))) {
            var defaults=new YamlConfiguration();
            try(var in=new java.io.InputStreamReader(Objects.requireNonNull(GameConfig.class.getResourceAsStream("/config.yml")),java.nio.charset.StandardCharsets.UTF_8)){defaults.load(in);}
            for(String key:addedUiKeys)if(!c.isSet(key))c.set(key,defaults.get(key));
        }
        // 실행 중인 구버전 관리자 창은 이전 상한을 다시 저장할 수 있습니다.
        // 사용자가 요청한 상한 변경만 별도 대기 파일로 보존하고, 다음 정상 시작 때 한 번 반영합니다.
        if(new File(directory,"pending-upgrade.yml").isFile()) {
            var upgrade=read(directory,"pending-upgrade.yml");integer(upgrade,"schema",1,1);
            c.set("game.max-price",java.math.BigDecimal.valueOf(money(upgrade,"max-price",1,MAX_PRICE_CENTS),2));
        }
        if(new File(directory,"pending-loan-upgrade.yml").isFile()) {
            var upgrade=read(directory,"pending-loan-upgrade.yml");integer(upgrade,"schema",1,1);
            c.set("loans.max-principal",java.math.BigDecimal.valueOf(money(upgrade,"max-principal",1,100000000000000L),2));
            for(String key:List.of("require-low-assets","allow-top-up")) {
                require(upgrade.isBoolean(key),key+": true/false 필요");c.set("loans."+key,upgrade.getBoolean(key));
            }
        }
        // 구버전 서버/관리자 창이 저장하는 동안에도 다음 시작에 반영할 변경만 따로 보관합니다.
        if(new File(directory,"pending-balance-upgrade.yml").isFile()) {
            var upgrade=read(directory,"pending-balance-upgrade.yml");integer(upgrade,"schema",1,1);
            for(String key:List.of("term-seconds","interest-interval-seconds","max-missed-payments"))
                c.set("loans."+key,integer(upgrade,key,1,key.equals("max-missed-payments")?1000:31536000));
            for(String key:List.of("interest-percent","late-fee-percent"))c.set("loans."+key,number(upgrade,key,0,100));
            require(upgrade.isBoolean("allow-building"),"allow-building: true/false 필요");
            m.set("protection.allow-building",upgrade.getBoolean("allow-building"));
        }
        // 이전 과도한 금리/파산 정책을 대체합니다. 다른 운영 설정과 실제 계정은 여기서 쓰지 않습니다.
        if(new File(directory,"pending-debt-safety-upgrade.yml").isFile()) {
            var upgrade=read(directory,"pending-debt-safety-upgrade.yml");integer(upgrade,"schema",1,1);
            for(String key:List.of("term-seconds","interest-interval-seconds","max-missed-payments","bankruptcy-grace-seconds"))
                c.set("loans."+key,integer(upgrade,key,key.equals("bankruptcy-grace-seconds")?0:1,key.equals("max-missed-payments")?1000:31536000));
            for(String key:List.of("interest-percent","late-fee-percent"))c.set("loans."+key,number(upgrade,key,0,100));
            require(upgrade.isBoolean("auto-liquidate-collateral"),"auto-liquidate-collateral: true/false 필요");
            c.set("loans.auto-liquidate-collateral",upgrade.getBoolean("auto-liquidate-collateral"));
            c.set("loans.collector.grace-seconds",integer(upgrade,"collector.grace-seconds",0,86400));
            c.set("loans.collector.attack-interval-ticks",integer(upgrade,"collector.attack-interval-ticks",1,1200));
            for(String key:List.of("damage","speed","spawn-distance"))c.set("loans.collector."+key,number(upgrade,"collector."+key,.1,10000));
            // 알려진 이전 기본 안내만 새 정책으로 교체합니다. 직접 편집한 안내는 보존합니다.
            var defaults=new YamlConfiguration();
            try(var in=new java.io.InputStreamReader(Objects.requireNonNull(GameConfig.class.getResourceAsStream("/config.yml")),java.nio.charset.StandardCharsets.UTF_8)){defaults.load(in);}
            var oldMessages=Map.of(
                    "loan-bankrupt","&4파산! 매수·대출·송금이 제한됩니다. 주식 매도·채무 상환은 가능합니다. /stock repay all",
                    "loan-status","&e원금 {principal}원 | 미납 이자 {interest}원 | 합계 {debt}원 | 만기까지 {seconds}초 | 연속 미납 {misses}/{limit} | {status}");
            oldMessages.forEach((key,old)->{if(old.equals(c.getString("messages."+key)))c.set("messages."+key,defaults.get("messages."+key));});
            var lore=new ArrayList<String>();
            for(String line:c.getStringList("ui.lender.loan-lore")) {
                if(line.equals("&7연속 미납 {limit}회 파산 · 추심 주민 출동")) {
                    lore.add("&7부족 금액 주식 자동 상환: {collateral}");lore.add("&7만기 후 {grace}초 유예 + {limit}회 미납 후 파산");lore.add("&7파산 시 잔존 주식 정산 · 추심 유예 {collectorGrace}초");
                }else lore.add(line);
            }
            c.set("ui.lender.loan-lore",lore);
        }
        if(new File(directory,"pending-recording-upgrade.yml").isFile()) {
            var upgrade=read(directory,"pending-recording-upgrade.yml");integer(upgrade,"schema",1,1);
            for(String key:List.of("game.broadcast-trades","loans.block-transfers-while-indebted")) {
                require(upgrade.isBoolean(key),key+": true/false 필요");c.set(key,upgrade.getBoolean(key));
            }
            String metric=text(upgrade,"ranking.metric");require(List.of("cash","equity","net-cash","net-equity").contains(metric),"ranking.metric: 지원하지 않는 순위 기준");m.set("ranking.metric",metric);
            if(c.getIntegerList("ui.buy-quantities").equals(List.of(1,10)))c.set("ui.buy-quantities",List.of(1,10,100));
            var changedLabels=Map.of(
                    "portfolio-header","&6현금 {cash} | 총 평가자산 {equity} | 실현손익 {realized}",
                    "actionbar","&6현금 &f{cash} &8| &6총 자산 &f{equity} &8| &6평가손익 {profitColor}{profit} &8| &e/stock",
                    "loan-setting-changed","&a{path} = {value} 저장 완료. 기존 대출 만기는 유지됩니다.");
            var defaults=new YamlConfiguration();try(var in=new java.io.InputStreamReader(Objects.requireNonNull(GameConfig.class.getResourceAsStream("/config.yml")),java.nio.charset.StandardCharsets.UTF_8)){defaults.load(in);}
            changedLabels.forEach((key,old)->{if(old.equals(c.getString("messages."+key)))c.set("messages."+key,defaults.get("messages."+key));});
            c.set("messages.sidebar",c.getStringList("messages.sidebar").stream().map(line->line.equals("&7총 자산 &e{equity}")?"&7순자산 &e{equity}":line).toList());
        }
        // 사채 단순화(선이자·만기 일시회수·즉시 파산)와 강한 주가 프리셋입니다. 다음 정상 시작에 한 번 반영합니다.
        if(new File(directory,"pending-loan-remake-upgrade.yml").isFile()) {
            var upgrade=read(directory,"pending-loan-remake-upgrade.yml");integer(upgrade,"schema",1,1);
            for(String key:List.of("term-seconds","interest-interval-seconds"))c.set("loans."+key,integer(upgrade,"loans."+key,1,31536000));
            for(String key:List.of("interest-percent","upfront-fee-percent"))c.set("loans."+key,number(upgrade,"loans."+key,0,100));
            c.set("loans.max-interest-percent",number(upgrade,"loans.max-interest-percent",0,1000));
            c.set("loans.due-warning-seconds",integer(upgrade,"loans.due-warning-seconds",0,3600));
            c.set("loans.collector.rest-seconds",integer(upgrade,"loans.collector-rest-seconds",0,3600));
            c.set("news.max-total-change-percent",number(upgrade,"news.max-total-change-percent",0,1000));
            c.set("news.reversal-chance",number(upgrade,"news.reversal-chance",0,1));
            // 예전 규칙(연체료·연속 미납·유예·페널티 모드)의 키와 안내문은 새 규칙과 모순되므로 지우고 새 기본 문구로 바꿉니다.
            for(String key:RETIRED_LOAN_KEYS)c.set(key,null);
            var defaults=bundledConfig();for(String key:LOAN_TEXT_KEYS)c.set(key,defaults.get(key));
            c.set("ui.loan-admin.fields",null); // 사라진 항목 칸을 비우고 새 기본 배치를 씁니다.
            c.set("ui.stock-lore",c.getStringList("ui.stock-lore").stream().map(line->line.equals("&7등락률: {color}{change}%")?"&71분 등락: {color}{change}%":line).toList());
        }
        long maxPrice = money(c, "game.max-price", 1, MAX_PRICE_CENTS);
        number(c,"game.volatility-reference-seconds",.05,86400);
        number(c,"game.max-step-change-percent",0,100);
        number(c,"game.volatility-scale",.05,5);
        number(c,"news.max-instant-change-percent",0,100);
        number(c,"news.max-total-change-percent",0,1000);
        number(c,"news.instant-scale",0,10);
        integer(c,"ui.change-window-seconds",0,3600);
        var quantities = c.getIntegerList("ui.buy-quantities");
        require(!quantities.isEmpty() && quantities.size() <= 5 && quantities.stream().allMatch(q -> q > 0 && q <= 1000000), "ui.buy-quantities: 1~5개의 양수 수량 필요");
        int newsMin = integer(c, "news.min-interval-seconds", 1, 86400);
        int newsMax = integer(c, "news.max-interval-seconds", newsMin, 86400);
        double maxTickSeconds=number(c,"game.max-tick-seconds",600,31536000);
        Rules rules = new Rules(money(c,"game.initial-cash",0,100000000000000L), c.getBoolean("game.auto-start"),
                number(c,"game.tick-seconds",0,maxTickSeconds), integer(c,"game.autosave-seconds",1,3600), maxPrice,
                integer(c,"game.max-shares-per-stock",1,1000000), number(c,"game.buy-fee-percent",0,20)/100,
                number(c,"game.sell-fee-percent",0,20)/100, c.getBoolean("game.trading-when-stopped"),
                c.getBoolean("game.teleport-on-first-join"), c.getBoolean("game.broadcast-trades"), newsMin, newsMax,
                number(c,"news.reversal-chance",0,1), integer(c,"ui.refresh-ticks",1,1200), List.copyOf(quantities),
                maxTickSeconds,integer(c,"ui.chart-refresh-ticks",1,1200));
        integer(c,"game.click-buy-quantity",1,1000000);integer(c,"game.click-sell-quantity",1,1000000);
        integer(c,"game.sneak-click-buy-quantity",1,1000000);integer(c,"game.sneak-click-sell-quantity",1,1000000);
        require(c.isBoolean("ui.sidebar-ranking-enabled"),"ui.sidebar-ranking-enabled: true/false 필요");
        integer(c,"ui.sidebar-ranking-limit",1,15);
        integer(c,"ui.money-decimals",0,2);integer(c,"ui.percent-decimals",0,2);
        require(c.isBoolean("ui.sidebar-holdings-enabled"),"ui.sidebar-holdings-enabled: true/false 필요");
        require(c.isBoolean("ui.actionbar-holdings-enabled"),"ui.actionbar-holdings-enabled: true/false 필요");
        integer(c,"ui.actionbar-page-ticks",1,1200);
        int shopSlot=integer(c,"ui.work-shop-slot",45,53);require(!List.of(45,49,53).contains(shopSlot),"ui.work-shop-slot: 45/49/53은 기존 메뉴 버튼입니다");
        material(c,"ui.work-shop-icon",false);text(c,"ui.work-shop-name");
        LinkedHashMap<String,Stock> stocks = new LinkedHashMap<>();
        var section = s.getConfigurationSection("stocks");
        require(section != null && !section.getKeys(false).isEmpty() && section.getKeys(false).size() <= 48, "stocks: 1~48개 종목 필요");
        for (String id : section.getKeys(false)) {
            require(id.matches("[a-z0-9_-]+"), "잘못된 종목 ID: " + id);
            var v = section.getConfigurationSection(id);
            long start = money(v,"start-price",1,maxPrice), delist = money(v,"delist-price",1,start-1);
            double min = number(v,"min-change-percent",0,100), max = number(v,"max-change-percent",min,100);
            String name = text(v,"name"); require(name.length() <= 96, id + ": 표시 이름은 96자 이하");
            stocks.put(id,new Stock(id,name,text(v,"sector"),material(v,"icon",false),start,delist,min,max,number(v,"base-rise-chance",0,1),
                    v.isSet("pedestal")?material(v,"pedestal",true):Material.CYAN_CONCRETE,
                    v.isSet("display-block")?material(v,"display-block",true):Material.GOLD_BLOCK,commandAliases(v),skinName(v.getString("skin-name",""))));
        }
        stockNameIndex(stocks); // 회사끼리 같은 이름/별칭을 쓰면 잘못된 종목을 선택하지 않도록 거부합니다.
        List<News> news = new ArrayList<>();
        var ns = n.getConfigurationSection("news"); require(ns != null && !ns.getKeys(false).isEmpty(), "news 목록 필요");
        for (String id : ns.getKeys(false)) {
            require(id.matches("[a-z0-9_-]+"), "잘못된 뉴스 ID: " + id);
            var v = ns.getConfigurationSection(id);
            News item = new News(id,text(v,"headline"),v.getStringList("stocks"),v.getStringList("sectors"),number(v,"weight",0,1000000),
                    integer(v,"duration-seconds",1,86400),number(v,"rise-chance",0,1),number(v,"multiplier",0,20),number(v,"immediate-percent",-100,1000));
            require(stocks.keySet().containsAll(item.stocks()),id+": 알 수 없는 대상 종목");
            require(stocks.values().stream().anyMatch(item::matches), id+": 대상 종목이 없습니다"); news.add(item);
        }
        require(news.stream().anyMatch(i -> i.weight()>0), "자동 뉴스의 weight 합계는 0보다 커야 합니다");
        require(text(m,"world-name").matches("[a-zA-Z0-9_-]+"),"world-name: 영문/숫자/_/-만 허용");
        for(String key:List.of("protection.allow-building","layout.preserve-builds")) {
            if(!m.isSet(key))m.set(key,!key.equals("protection.allow-building"));require(m.isBoolean(key),key+": true/false 필요");
        }
        integer(m,"origin.x",-1000000,1000000); integer(m,"origin.z",-1000000,1000000); integer(m,"origin.y",0,200);
        integer(m,"chart.width",8,48); integer(m,"chart.height",6,24); integer(m,"chart.history-size",m.getInt("chart.width"),500);
        integer(m,"chart.floor-offset",2,10); integer(m,"layout.panel-columns",1,8);
        integer(m,"layout.panel-gap",3,20); integer(m,"layout.row-gap",12,50); integer(m,"layout.floor-margin",4,20);
        integer(m,"layout.spawn-z-offset",3,m.getInt("layout.row-gap")-2);
        integer(m,"layout.ceiling-height",m.getInt("chart.floor-offset")+m.getInt("chart.height")+4,40);
        integer(m,"layout.build-blocks-per-tick",100,20000);
        for (String key : List.of("up","down","flat","background","grid")) material(m,"chart."+key,true);
        for (String key : List.of("floor","aisle","wall","glass","trim","light","counter","roof")) material(m,"palette."+key,true);
        for (String key : List.of("entrance","help","terminal","panel")) text(m,"text."+key);
        require(List.of("kiosks","wall").contains(m.getString("layout.style","wall")),"layout.style: kiosks 또는 wall");
        if(m.getString("layout.style","wall").equals("kiosks")) {
            if(!m.isSet("kiosk.display-mode"))m.set("kiosk.display-mode","player");
            if(!m.isSet("kiosk.npc-height"))m.set("kiosk.npc-height",1.0);
            if(!m.isSet("kiosk.npc-yaw"))m.set("kiosk.npc-yaw",0);
            require(List.of("player","block").contains(text(m,"kiosk.display-mode")),"kiosk.display-mode: player 또는 block");
            number(m,"kiosk.npc-height",0,8);number(m,"kiosk.npc-yaw",-360,360);
            integer(m,"kiosk.columns",1,8);number(m,"kiosk.spacing",6,32);number(m,"kiosk.row-spacing",10,50);
            integer(m,"kiosk.margin",6,30);integer(m,"kiosk.time",0,23999);material(m,"kiosk.floor",true);
            integer(m,"kiosk.pedestal-width",1,5);integer(m,"kiosk.pedestal-height",1,3);
            integer(m,"kiosk.history-lines",2,20);number(m,"kiosk.history-height",3,20);number(m,"kiosk.name-height",2,10);
            integer(m,"kiosk.scroll-ticks",0,59);number(m,"kiosk.line-spacing",.15,1.5);
            number(m,"kiosk.block-height",2,8);number(m,"kiosk.block-scale",0.25,3);number(m,"kiosk.text-scale",0.5,3);
            integer(m,"kiosk.name-line-width",80,800);number(m,"kiosk.portfolio-x",1,8);number(m,"kiosk.portfolio-y",1,8);
            number(m,"kiosk.leaderboard-x",-100,100);number(m,"kiosk.leaderboard-y",1,30);number(m,"kiosk.leaderboard-z",-100,100);
            number(m,"kiosk.leaderboard-scale",0.5,3);integer(m,"kiosk.leaderboard-line-width",100,2000);
            number(m,"kiosk.large-change-percent",0,100);integer(m,"kiosk.price-decimals",0,2);
            for(String key:List.of("rise-color","fall-color","flat-color","rise-symbol","large-rise-symbol","fall-symbol","large-fall-symbol","flat-symbol"))text(m,"kiosk."+key);
            for(String key:List.of("portfolio","leaderboard-title","leaderboard-row"))text(m,"text."+key);
            for(String key:List.of("news-offset-x","news-offset-y","news-offset-z"))number(m,"kiosk."+key,-100,100);
            number(m,"kiosk.news-scale",.3,3);integer(m,"kiosk.news-line-width",100,2000);
            require(List.of("cash","equity","net-cash","net-equity").contains(text(m,"ranking.metric")),"ranking.metric: cash/equity/net-cash/net-equity");
            integer(m,"ranking.max-entries",1,50);number(m,"ranking.x",-100,100);number(m,"ranking.y",1,30);number(m,"ranking.z",-100,100);
            number(m,"ranking.scale",.3,3);integer(m,"ranking.line-width",100,2000);
            require(m.isBoolean("ranking.world-enabled"),"ranking.world-enabled: true/false 필요");
            for(String key:List.of("news-panel","news-idle","ranking-title","ranking-row","ranking-empty","delisted-row","delisted-name"))text(m,"text."+key);
        }
        for (String key : List.of("title","scoreboard-title","currency","rise-color","fall-color","flat-color")) text(c,"ui."+key);
        require(!c.getStringList("ui.stock-lore").isEmpty(),"ui.stock-lore 목록 필요");
        for(String key:List.of("prefix","welcome","started","stopped","closed","no-permission","reloaded","interval-changed","initialcash-changed","reset-confirm","reset-done","error","delisted","news-chat","news-title","buy","sell","portfolio-header","portfolio-line","button-buy","button-sell","button-buy-all","button-sell-all","button-back","button-next","button-previous","button-portfolio","state-open","state-closed","state-delisted","holding-line","actionbar")) text(c,"messages."+key);
        require(c.getStringList("messages.sidebar").size()<=10,"sidebar 기본 줄은 최대 10개");
        text(c,"messages.sidebar-ranking-row");text(c,"messages.sidebar-ranking-empty");
        text(c,"messages.actionbar-holding");
        validateLender(c);
        LoanAdminUi.layout(c);
        StatePersistence.Options.from(c);integer(c,"persistence.config-backup-retention",1,1000);
        require(c.isBoolean("loans.block-transfers-while-indebted"),"loans.block-transfers-while-indebted: true/false 필요");
        var result=new GameConfig(rules,stocks,List.copyOf(news),c,m);result.loanRules();return result;
    }
    private static void validateLender(YamlConfiguration c) {
        text(c,"loans.npc-name");
        for(String key:List.of("title","borrow-name","repay-name","status-name","close-name","eligibility-open","eligibility-limited"))text(c,"ui.lender."+key);
        for(String key:List.of("borrow-icon","repay-icon","status-icon","close-icon"))material(c,"ui.lender."+key,false);
        require(!c.getStringList("ui.lender.loan-lore").isEmpty()&&!c.getStringList("ui.lender.status-lore").isEmpty(),"ui.lender: 안내 문구 목록 필요");
        var raw=c.getList("ui.lender.borrow-slots");require(raw!=null&&raw.size()==3,"ui.lender.borrow-slots: 슬롯 3개 필요");
        var slots=new HashSet<Integer>();
        for(Object slot:raw) {require(slot instanceof Number,"대출 버튼 슬롯은 정수 필요");double n=((Number)slot).doubleValue();require(n==Math.rint(n)&&n>=0&&n<27&&slots.add((int)n),"대출 버튼 슬롯은 서로 다른 0~26 정수 필요");}
        for(String key:List.of("repay-slot","status-slot","close-slot","admin-slot"))require(slots.add(integer(c,"ui.lender."+key,0,26)),"사채업자 메뉴 슬롯 중복: "+key);
        material(c,"ui.lender.admin-icon",false);text(c,"ui.lender.admin-name");
        require(c.isBoolean("loans.collector.enabled"),"loans.collector.enabled: true/false 필요");text(c,"loans.collector.name");
        try{org.bukkit.entity.Villager.Profession.valueOf(text(c,"loans.collector.profession"));}catch(IllegalArgumentException e){throw new IllegalArgumentException("loans.collector.profession: 잘못된 주민 직업");}
        number(c,"loans.collector.spawn-distance",1,16);number(c,"loans.collector.speed",.1,3);
        number(c,"loans.collector.attack-range",.5,5);number(c,"loans.collector.damage",.1,10000);
        integer(c,"loans.collector.attack-interval-ticks",1,1200);integer(c,"loans.collector.update-ticks",1,20);
        number(c,"loans.collector.max-distance",8,256);
        integer(c,"loans.collector.grace-seconds",0,86400);
        integer(c,"loans.collector.rest-seconds",0,3600);integer(c,"loans.due-warning-seconds",0,3600);
    }
    private static String skinName(String value) {
        require(value!=null&&(value.isEmpty()||value.matches("[A-Za-z0-9_]{3,16}")),"skin-name: 빈 값 또는 Minecraft 닉네임 3~16자 필요");return value;
    }
    public GameConfig withSkin(String id,String nickname) {
        Stock s=stocks.get(id);require(s!=null,"알 수 없는 회사: "+id);skinName(nickname);
        var updated=new LinkedHashMap<>(stocks);
        updated.put(id,new Stock(s.id(),s.name(),s.sector(),s.icon(),s.start(),s.delist(),s.minChange(),s.maxChange(),s.riseChance(),s.pedestal(),s.displayBlock(),s.commandAliases(),nickname));
        return new GameConfig(rules,updated,news,ui,map);
    }
    /** 대출의 금액은 원→전, 시간은 시장이 열린 동안의 서버 초로 읽습니다. */
    public LoanRules loanRules() {
        require(ui.isBoolean("loans.enabled"),"loans.enabled: true/false 필요");
        require(ui.isBoolean("loans.require-low-assets"),"loans.require-low-assets: true/false 필요");
        require(ui.isBoolean("loans.allow-top-up"),"loans.allow-top-up: true/false 필요");
        long min=money(ui,"loans.min-principal",1,100000000000000L),max=money(ui,"loans.max-principal",min,100000000000000L);
        double maxInterest=number(ui,"loans.max-interest-percent",0,1000),upfront=number(ui,"loans.upfront-fee-percent",0,90);
        // 선이자도 총이자에 포함되므로 상한보다 크면 빌리는 순간 계약 모순이 됩니다.
        require(upfront<=maxInterest,"loans.upfront-fee-percent: 총이자 상한(max-interest-percent) 이하여야 합니다");
        return new LoanRules(ui.getBoolean("loans.enabled"),money(ui,"loans.bankruptcy-threshold",0,100000000000000L),min,max,
                money(ui,"loans.default-principal",min,max),integer(ui,"loans.term-seconds",1,31536000),
                integer(ui,"loans.interest-interval-seconds",1,31536000),number(ui,"loans.interest-percent",0,100),
                maxInterest,upfront,ui.getBoolean("loans.require-low-assets"),ui.getBoolean("loans.allow-top-up"));
    }
    /** 파일에 남은 테스트용 초단위 값은 시작을 막지 않고 운영자에게만 알립니다. */
    public List<String> loanTimingWarnings() {
        var r=loanRules();var warnings=new ArrayList<String>();
        if(r.interestIntervalSeconds()<MIN_ADMIN_INTEREST_INTERVAL_SECONDS)warnings.add("loans.interest-interval-seconds="+r.interestIntervalSeconds()+"초: 관리자 창 최소값("+MIN_ADMIN_INTEREST_INTERVAL_SECONDS+"초)보다 짧습니다. 테스트 값이면 되돌리세요");
        if(r.termSeconds()<MIN_ADMIN_TERM_SECONDS)warnings.add("loans.term-seconds="+r.termSeconds()+"초: 관리자 창 최소값("+MIN_ADMIN_TERM_SECONDS+"초)보다 짧습니다. 새 대출이 거의 즉시 만기됩니다");
        return List.copyOf(warnings);
    }
    private static List<String> commandAliases(ConfigurationSection section) {
        if(!section.isSet("command-aliases"))return List.of();
        Object raw=section.get("command-aliases");require(raw instanceof List<?>,"command-aliases: 문자열 목록 필요");
        var result=new ArrayList<String>();
        for(Object entry:(List<?>)raw) {
            require(entry instanceof String,"command-aliases: 문자열만 사용하세요");
            String value=(String)entry;
            require(!value.isBlank()&&value.length()<=96&&!value.chars().anyMatch(Character::isISOControl)&&!stockToken(value).isEmpty(),"command-aliases: 비어 있지 않은 96자 이하 이름 필요");
            result.add(value);
        }
        return List.copyOf(result);
    }
    private static String stockToken(String value) {
        require(value!=null,"회사 이름을 입력하세요");
        return java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKC)
                .replaceAll("(?i)[&§][0-9a-fk-orx]","").replaceAll("(?U)\\s+","");
    }
    private static Map<String,String> stockNameIndex(Map<String,Stock> stocks) {
        var index=new LinkedHashMap<String,String>();
        for(var s:stocks.values()) {
            var names=new ArrayList<String>();names.add(s.id());names.add(s.name());names.addAll(s.commandAliases());
            for(String name:names) {
                String key=stockToken(name).toLowerCase(Locale.ROOT);require(!key.isEmpty(),"회사 이름/별칭은 비울 수 없습니다");
                String previous=index.putIfAbsent(key,s.id());
                require(previous==null||previous.equals(s.id()),"다른 회사와 중복된 이름/별칭: "+name);
            }
        }
        return index;
    }
    /** 전체 회사명/공백을 뺀 이름/설정 별칭/구버전 ID를 같은 저장 ID로 연결합니다. */
    public String resolveStock(String input) {
        String id=stockNameIndex(stocks).get(stockToken(input).toLowerCase(Locale.ROOT));
        require(id!=null,"알 수 없는 회사: "+input);return id;
    }
    public String stockCommandName(String id) {
        Stock stock=stocks.get(id);require(stock!=null,"알 수 없는 회사: "+id);
        return stockToken(stock.commandAliases().isEmpty()?stock.name():stock.commandAliases().getFirst());
    }
    /** TAB과 짧은 자산 표시는 첫 별칭을 사용하며 내부 저장 ID를 노출하지 않습니다. */
    public List<String> stockCommandNames() {return stocks.keySet().stream().map(this::stockCommandName).toList();}
    private static YamlConfiguration read(File directory,String name) throws Exception {
        var result = new YamlConfiguration(); result.options().parseComments(true); result.load(new File(directory,name)); return result;
    }
    /** 0초도 무한 반복하지 않고 최소 서버 1틱으로 처리합니다. */
    public static long intervalMillis(double seconds) {
        require(Double.isFinite(seconds)&&seconds>=0&&seconds<=31536000,"주가 갱신 간격은 유효한 0 이상의 숫자여야 합니다");
        return Math.max(1L,(long)Math.ceil(seconds*20.0))*50L;
    }
    public GameConfig withInterval(double seconds) throws Exception {
        require(Double.isFinite(seconds)&&seconds>=0&&seconds<=rules.maxTickSeconds(),"주가 갱신 간격 허용 범위: 0~"+rules.maxTickSeconds()+"초");
        var r=rules;
        var updated=new Rules(r.initialCash(),r.autoStart(),seconds,r.autosaveSeconds(),r.maxPrice(),r.maxShares(),r.buyFee(),r.sellFee(),
                r.tradeStopped(),r.teleportFirst(),r.broadcastTrades(),r.newsMin(),r.newsMax(),r.reversal(),r.refreshTicks(),r.quantities(),r.maxTickSeconds(),r.chartRefreshTicks());
        var changed=new YamlConfiguration();changed.options().parseComments(true);changed.loadFromString(ui.saveToString());changed.set("game.tick-seconds",seconds);
        return new GameConfig(updated,stocks,news,changed,map);
    }
    public GameConfig withInitialCash(long cents) throws Exception {
        require(cents>=0&&cents<=100000000000000L,"초기 자금 허용 범위: 0~1조 원, 소수 둘째 자리까지");
        var r=rules;
        var updated=new Rules(cents,r.autoStart(),r.tickSeconds(),r.autosaveSeconds(),r.maxPrice(),r.maxShares(),r.buyFee(),r.sellFee(),
                r.tradeStopped(),r.teleportFirst(),r.broadcastTrades(),r.newsMin(),r.newsMax(),r.reversal(),r.refreshTicks(),r.quantities(),r.maxTickSeconds(),r.chartRefreshTicks());
        var changed=new YamlConfiguration();changed.options().parseComments(true);changed.loadFromString(ui.saveToString());changed.set("game.initial-cash",java.math.BigDecimal.valueOf(cents,2));
        return new GameConfig(updated,stocks,news,changed,map);
    }
    /** 관리자 입력은 허용된 대출 키만 변경하며, 잘못된 값은 원본 설정에 손대지 않습니다. */
    public GameConfig withLoanSetting(String path,String value) throws Exception {
        String key=path.startsWith("loans.")?path.substring(6):path;
        var booleans=Set.of("enabled","require-low-assets","allow-top-up");
        var moneyKeys=Set.of("min-principal","max-principal","default-principal");
        var integers=Set.of("term-seconds","interest-interval-seconds","due-warning-seconds","collector.grace-seconds","collector.rest-seconds","collector.attack-interval-ticks");
        var decimals=Set.of("interest-percent","max-interest-percent","upfront-fee-percent","collector.damage");
        require(booleans.contains(key)||moneyKeys.contains(key)||integers.contains(key)||decimals.contains(key),"수정할 수 없는 대출 설정: "+path);
        require(value!=null&&!value.isBlank(),"새 값을 입력하세요");
        var changed=new YamlConfiguration();changed.options().parseComments(true);changed.loadFromString(ui.saveToString());
        Object parsed;
        if(booleans.contains(key)) {
            require(value.equalsIgnoreCase("true")||value.equalsIgnoreCase("false"),"true 또는 false로 입력하세요");parsed=Boolean.parseBoolean(value);
        } else {
            java.math.BigDecimal number;
            try{number=new java.math.BigDecimal(value.strip());}catch(NumberFormatException e){throw new IllegalArgumentException("유효한 숫자를 입력하세요");}
            if(integers.contains(key)) {
                try{parsed=number.intValueExact();}catch(ArithmeticException e){throw new IllegalArgumentException("시간은 정수로 입력하세요");}
            }else parsed=number;
        }
        // 관리자 창의 실수 입력만 막습니다. 파일 직접 편집 값은 기존 범위로 읽고 시작 시 경고합니다.
        if(key.equals("interest-interval-seconds"))require((Integer)parsed>=MIN_ADMIN_INTEREST_INTERVAL_SECONDS,"이자 붙는 간격은 최소 "+MIN_ADMIN_INTEREST_INTERVAL_SECONDS+"초입니다. 더 짧으면 몇 초 만에 이자 상한에 닿습니다");
        if(key.equals("term-seconds"))require((Integer)parsed>=MIN_ADMIN_TERM_SECONDS,"대출 만기는 최소 "+MIN_ADMIN_TERM_SECONDS+"초입니다");
        changed.set("loans."+key,parsed);
        var result=new GameConfig(rules,stocks,news,changed,map);result.loanRules();validateLender(changed);return result;
    }
    static void require(boolean ok,String reason) { if (!ok) throw new IllegalArgumentException(reason); }
    static String text(ConfigurationSection c,String path) {
        require(c != null && c.isString(path) && !c.getString(path,"").isBlank(),path+": 문자열 필요"); return c.getString(path);
    }
    static double number(ConfigurationSection c,String path,double min,double max) {
        require(c != null && c.isSet(path) && c.get(path) instanceof Number,path+": 숫자 필요"); double d=c.getDouble(path);
        require(Double.isFinite(d) && d>=min && d<=max,path+": 허용 범위 "+min+"~"+max); return d;
    }
    static int integer(ConfigurationSection c,String path,int min,int max) {
        double d=number(c,path,min,max); require(d==Math.rint(d),path+": 정수 필요"); return (int)d;
    }
    static long money(ConfigurationSection c,String path,long min,long max) {
        require(c != null && c.isSet(path),path+": 금액 필요"); long v=Market.cents(c.get(path).toString());
        require(v>=min && v<=max,path+": 금액 범위 초과"); return v;
    }
    static Material material(ConfigurationSection c,String path,boolean block) {
        var result=Material.matchMaterial(text(c,path));
        // 1.20.6의 isItem/isBlock은 서버 레지스트리를 요구합니다. 이름 검증은 항상,
        // 아이템/블록 분류 검증은 실제 서버 로드에서 수행합니다.
        require(result!=null && result!=Material.AIR && (org.bukkit.Bukkit.getServer()==null || (block?result.isBlock():result.isItem())),path+": 잘못된 재료"); return result;
    }
    /** 모든 NPC/블록 거래 경로에서 클릭 순간의 웅크리기 상태로 같은 수량 설정을 선택합니다. */
    public int terminalQuantity(boolean buying,boolean sneaking) {
        return ui.getInt("game."+(sneaking?"sneak-":"")+"click-"+(buying?"buy":"sell")+"-quantity");
    }
    public String msg(String key) { return ui.getString("messages."+key,key); }
}
