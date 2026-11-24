package kr.hetagram.stock;

import net.kyori.adventure.title.Title;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.EquipmentSlot;
import java.time.Duration;
import java.util.*;

public final class StockPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener,CommandExecutor,TabCompleter {
    private GameConfig settings;
    private Market market;
    private Loans loans;
    private LenderSettings lenderSettings;
    private LenderNpc lender;
    private DebtCollectors collectors;
    private LoanAdminUi loanAdmin;
    private WorkSettings workSettings;
    private WorkArea workArea;
    private WorkShop workShop;
    private ParkourSettings parkourSettings;
    private ParkourArea parkour;
    private StateStore store;
    private StatePersistence persistence;
    private RoundResources resources;
    private TradingUi ui;
    private ExchangeMap exchange;
    private boolean loaded;
    private final Random random=new Random();
    private final List<BukkitTask> tasks=new ArrayList<>();
    private long nextPrice,serverTicks;
    private boolean chartDirty;
    private boolean hasAdminSnapshot;
    private int lastAdminSnapshotTick;
    private final TerminalClickGate clickGate=new TerminalClickGate();
    public GameConfig settings() { return settings; }
    public Market market() { return market; }
    WorkSettings workSettings(){return workSettings;}
    RoundResources resources(){return resources;}
    GameConfig.LoanRules loanRulesFor(Player player){return loans.rulesFor(player.getUniqueId());}
    void requireFinancialState(){if(persistence!=null)persistence.requireHealthy();}
    @Override public void onEnable() {
        try {
            for(String file:List.of("config.yml","stocks.yml","news.yml","map.yml","work.yml","parkour.yml"))if(!new java.io.File(getDataFolder(),file).exists())saveResource(file,false);
            settings=GameConfig.load(getDataFolder());market=new Market(settings);store=new StateStore(getDataFolder());store.load(market);loans=new Loans(market);lenderSettings=LenderSettings.load(getDataFolder());
            workSettings=WorkSettings.load(getDataFolder());
            parkourSettings=ParkourSettings.load(getDataFolder());
            var pending=List.of(getDataFolder().toPath().resolve("pending-upgrade.yml"),getDataFolder().toPath().resolve("pending-loan-upgrade.yml"),getDataFolder().toPath().resolve("pending-balance-upgrade.yml"),getDataFolder().toPath().resolve("pending-debt-safety-upgrade.yml"),getDataFolder().toPath().resolve("pending-recording-upgrade.yml"),getDataFolder().toPath().resolve("pending-loan-remake-upgrade.yml"));
            if(pending.stream().anyMatch(java.nio.file.Files::exists)) {
                var config=getDataFolder().toPath().resolve("config.yml");
                java.nio.file.Files.copy(config,config.resolveSibling("config.yml.bak"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                atomicWrite(config,settings.ui().saveToString());
                if(java.nio.file.Files.exists(getDataFolder().toPath().resolve("pending-balance-upgrade.yml"))||java.nio.file.Files.exists(getDataFolder().toPath().resolve("pending-recording-upgrade.yml"))) {
                    var mapFile=getDataFolder().toPath().resolve("map.yml");java.nio.file.Files.copy(mapFile,mapFile.resolveSibling("map.yml.bak"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    atomicWrite(mapFile,settings.map().saveToString());
                }
                if(java.nio.file.Files.exists(getDataFolder().toPath().resolve("pending-recording-upgrade.yml"))) {
                    var workFile=getDataFolder().toPath().resolve("work.yml");java.nio.file.Files.copy(workFile,workFile.resolveSibling("work.yml.bak"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);atomicWrite(workFile,workSettings.yaml().saveToString());
                }
                for(var upgrade:pending)java.nio.file.Files.deleteIfExists(upgrade);
            }
            ui=new TradingUi(this);exchange=new ExchangeMap(this);lender=new LenderNpc(this);collectors=new DebtCollectors(this);
            loanAdmin=new LoanAdminUi(this);workShop=new WorkShop(this,workSettings);
            resources=new RoundResources(this);
            persistence=new StatePersistence(store,StatePersistence.Options.from(settings.ui()),r->{try{if(isEnabled())Bukkit.getScheduler().runTask(this,r);}catch(org.bukkit.plugin.IllegalPluginAccessException ignored){}},this::persistenceFailed,e->getLogger().warning("관리자 상태 저장 실패: "+e.getMessage()));loaded=true;
            Objects.requireNonNull(getCommand("stock")).setExecutor(this);getCommand("stock").setTabCompleter(this);
            getServer().getPluginManager().registerEvents(this,this);getServer().getPluginManager().registerEvents(ui,this);
            getServer().getPluginManager().registerEvents(loanAdmin,this);getServer().getPluginManager().registerEvents(workShop,this);
            getServer().getPluginManager().registerEvents(resources,this);
            // 플러그인 기본 POSTWORLD 단계에서 전용 월드를 생성합니다.
            exchange.open();schedule();
            tasks.add(Bukkit.getScheduler().runTask(this,()->{
                try{lender.apply(lenderSettings);}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"사채업자 복원 실패. 저장 위치는 유지합니다",e);}
            }));
            for(var entry:market.accounts.entrySet())if(entry.getValue().name.isBlank()) {
                String name=Bukkit.getOfflinePlayer(entry.getKey()).getName();if(name!=null)entry.getValue().name=name;
            }
            for(Player p:Bukkit.getOnlinePlayers())if(!p.hasMetadata("NPC"))market.account(p.getUniqueId()).name=p.getName();
            persistence.flushNow(market);getLogger().info("StockGame ready: "+market.quotes.size()+" stocks, market="+(market.running?"OPEN":"CLOSED"));
            settings.loanTimingWarnings().forEach(getLogger()::warning);
        } catch(Exception e) { getLogger().log(java.util.logging.Level.SEVERE,"시작 중단. 기존 데이터를 초기화하지 않습니다: "+e.getMessage(),e);getServer().getPluginManager().disablePlugin(this); }
    }
    private void schedule() {
        tasks.forEach(BukkitTask::cancel);tasks.clear();nextPrice=market.clockMillis+GameConfig.intervalMillis(settings.rules().tickSeconds());
        if(market.nextNewsMillis<=market.clockMillis)planNews();serverTicks=0;chartDirty=false;
        tasks.add(Bukkit.getScheduler().runTaskTimer(this,()->{
            serverTicks++;
            if(market.running&&!persistence.hasFatalFailure()) {
                market.clockMillis+=50;
                saveState();
                if(market.clockMillis>=nextPrice) {
                    var before=delisted();market.tick(random);announceDelisted(before);nextPrice=market.clockMillis+GameConfig.intervalMillis(settings.rules().tickSeconds());chartDirty=true;
                }
                if(market.clockMillis>=market.nextNewsMillis) { fireNews(null,null);planNews(); }
            }
            exchange.tickTickers();
            if(workArea==null&&exchange.ready()) {
                workArea=new WorkArea(this,exchange.world(),workSettings);
                try{workArea.open();}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"채굴장·낚시터 설치 실패. 기존 건축은 보존합니다",e);}
            }
            if(parkour==null&&exchange.ready()) {
                parkour=new ParkourArea(this,exchange.world(),parkourSettings);parkour.open();
            }
            if(chartDirty&&serverTicks%settings.rules().chartRefreshTicks()==0){exchange.render();chartDirty=false;}
            if(serverTicks%20==0){advanceLoans();writeAdminStatus();}
            collectors.tick();
            if(serverTicks%(settings.rules().autosaveSeconds()*20L)==0)saveState();
            persistence.tick(market);
        },1,1));
        tasks.add(Bukkit.getScheduler().runTaskTimer(this,()->{ui.refresh();exchange.refreshPortfolios();},1,settings.rules().refreshTicks()));
        writeAdminStatus();
    }
    private void writeAdminStatus() {
        int tick=Bukkit.getCurrentTick();if(hasAdminSnapshot&&tick-lastAdminSnapshotTick<settings.ui().getInt("persistence.flush-interval-ticks",20))return;
        hasAdminSnapshot=true;lastAdminSnapshotTick=tick;writeAdminStatusSnapshot();
    }
    private void writeAdminStatusSnapshot() {
        Map<String,Object> snapshot=new LinkedHashMap<>();snapshot.put("updatedAt",System.currentTimeMillis());
        snapshot.put("running",market.running);snapshot.put("intervalSeconds",settings.rules().tickSeconds());
        snapshot.put("effectiveIntervalSeconds",GameConfig.intervalMillis(settings.rules().tickSeconds())/1000.0);
        snapshot.put("maxIntervalSeconds",settings.rules().maxTickSeconds());snapshot.put("mapReady",exchange.ready());
        snapshot.put("clockMillis",market.clockMillis);
        snapshot.put("initialCash",settings.rules().initialCash()/100.0);snapshot.put("resetGeneration",market.resetGeneration);
        snapshot.put("moneyDecimals",settings.ui().getInt("ui.money-decimals",0));snapshot.put("percentDecimals",settings.ui().getInt("ui.percent-decimals",0));
        snapshot.put("stocks",market.quotes.values().stream().map(q->{Map<String,Object> row=new LinkedHashMap<>();row.put("id",q.spec.id());row.put("name",q.spec.name());row.put("price",q.price/100.0);row.put("change",q.change());row.put("delisted",q.delisted);row.put("candles",q.history.size());return row;}).toList());
        persistence.status(new com.google.gson.Gson().toJson(snapshot));
    }
    private static void atomicWrite(java.nio.file.Path file,String content) throws Exception {
        var tmp=file.resolveSibling(file.getFileName()+".tmp");java.nio.file.Files.writeString(tmp,content,java.nio.charset.StandardCharsets.UTF_8);
        try{java.nio.file.Files.move(tmp,file,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
        catch(java.nio.file.AtomicMoveNotSupportedException e){java.nio.file.Files.move(tmp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    }
    private void changeInterval(CommandSender sender,double seconds) throws Exception {
        GameConfig updated=settings.withInterval(seconds);
        persistSettings(updated,Map.of("game.tick-seconds",seconds));
        tell(sender,settings.msg("interval-changed").replace("{seconds}",Double.toString(seconds)).replace("{effective}",Double.toString(GameConfig.intervalMillis(seconds)/1000.0)));
    }
    private void persistSettings(GameConfig updated,Map<String,Object> changes) throws Exception {
        // 설정 저장에 성공한 뒤에만 실행 중 간격을 바꿉니다. 보유 자산과 주가는 그대로입니다.
        var file=getDataFolder().toPath().resolve("config.yml");
        ConfigEdits.patch(file,changes,settings.ui().getInt("persistence.config-backup-retention"),candidate->GameConfig.load(getDataFolder(),candidate));
        market.reconfigure(updated);settings=updated;persistence.reconfigure(StatePersistence.Options.from(settings.ui()));schedule();saveState();
    }
    void changeLoanSetting(CommandSender sender,String path,String value) throws Exception {
        GameConfig.require(sender.hasPermission("stock.admin"),settings.msg("no-permission"));
        GameConfig updated=settings.withLoanSetting(path,value);String key=path.startsWith("loans.")?path:"loans."+path;persistSettings(updated,Map.of(key,updated.ui().get(key)));
        tell(sender,Text.fill(settings.msg("loan-setting-changed"),Map.of("path",path,"value",value)));ui.refresh();collectors.refresh();
    }
    void openLoanAdmin(Player player){loanAdmin.open(player);}
    void openWorkShop(Player player){workShop.open(player);}
    void goParkour(Player player){GameConfig.require(parkour!=null&&parkour.ready(),parkourSettings.text("not-ready"));parkour.start(player);}
    void incomeChanged(Player player){loans.reconcile(player.getUniqueId());advanceLoans();collectors.refresh();saveState();ui.refreshPlayer(player);exchange.refreshPortfolios();writeAdminStatus();}
    void goWork(Player player,boolean fishing) {
        GameConfig.require(workArea!=null&&workArea.ready(),"채굴장·낚시터 준비 중이거나 설치할 공간이 없습니다. 서버 로그를 확인하세요");
        GameConfig.require(!player.hasMetadata("NPC")&&!player.isDead()&&player.getGameMode()!=GameMode.SPECTATOR,"실제 플레이어만 사용할 수 있습니다");
        GameConfig.require(player.teleport(fishing?workArea.pondSpawn():workArea.mineSpawn()),"이동이 취소되었습니다");workShop.kit(player);
    }
    private void resetGame(CommandSender sender) throws Exception {
        requireFinancialState();var backup=persistence.backupBeforeReset(market);getLogger().info("게임 초기화 전 백업: "+backup);
        ui.closeMenus();workShop.close();loanAdmin.clearMenus();clickGate.clear();market.reset();if(parkour!=null)parkour.reset();
        collectors.refresh();
        for(Player p:Bukkit.getOnlinePlayers())if(!p.hasMetadata("NPC"))market.account(p.getUniqueId()).name=p.getName();
        schedule();exchange.render();exchange.refreshPortfolios();ui.refresh();persistence.flushNow(market);resources.afterReset();writeAdminStatus();
        tell(sender,settings.msg("reset-done").replace("{backup}",backup.getFileName().toString()));
    }
    void executeTrade(Player p,String stock,boolean buying,long quantity) {
        requireFinancialState();
        advanceLoans();
        Market.Trade trade=buying?market.buy(p.getUniqueId(),stock,quantity):market.sell(p.getUniqueId(),stock,quantity);
        if(!buying){loans.reconcile(p.getUniqueId());advanceLoans();collectors.refresh();}
        saveState();String message=Text.fill(settings.msg(buying?"buy":"sell"),Map.of("player",p.getName(),"stock",market.quote(stock).spec.name(),"quantity",Long.toString(trade.quantity()),"amount",Text.money(settings,trade.amount()),"currency",settings.ui().getString("ui.currency")));
        if(settings.rules().broadcastTrades())broadcast(message);else tell(p,message);
        ui.refreshPlayer(p);exchange.refreshPortfolios();writeAdminStatus();
    }
    void terminalTrade(Player p,String stock,boolean buying) {
        if(stock==null||!market.quotes.containsKey(stock)||p.hasMetadata("NPC")||p.isDead()||p.getGameMode()==GameMode.SPECTATOR)return;
        if(!clickGate.allow(p.getUniqueId(),Bukkit.getCurrentTick()))return;
        try{executeTrade(p,stock,buying,settings.terminalQuantity(buying,p.isSneaking()));}
        catch(Exception e){tell(p,settings.msg("error").replace("{reason}",Objects.toString(e.getMessage(),e.getClass().getSimpleName())));}
    }
    private void planNews() { market.nextNewsMillis=market.clockMillis+(settings.rules().newsMin()+random.nextInt(settings.rules().newsMax()-settings.rules().newsMin()+1))*1000L; }
    private void changeSkin(CommandSender sender,String stock,String nickname) throws Exception {
        GameConfig.require(nickname.matches("[A-Za-z0-9_]{3,16}"),"Minecraft 닉네임은 영문/숫자/밑줄 3~16자로 입력하세요");
        GameConfig.require(exchange.hasPlayerTraders(),"플레이어 거래원 모드의 NPC가 준비된 뒤 사용하세요");
        GameConfig updated=settings.withSkin(stock,nickname);
        var file=getDataFolder().toPath().resolve("stocks.yml");
        var yaml=new org.bukkit.configuration.file.YamlConfiguration();yaml.options().parseComments(true);yaml.load(file.toFile());
        GameConfig.require(yaml.isConfigurationSection("stocks."+stock),"운영 stocks.yml에서 회사가 사라졌습니다. 설정을 확인하세요");
        // 편집 중인 다른 종목/뉴스/플레이어 상태를 기본값으로 덮지 않습니다.
        // 스킨 외 수동 편집은 파일에 그대로 보존하고 /stock reload 시에만 별도로 반영합니다.
        yaml.set("stocks."+stock+".skin-name",nickname);
        java.nio.file.Files.copy(file,file.resolveSibling("stocks.yml.bak"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        atomicWrite(file,yaml.saveToString());market.reconfigure(updated);settings=updated;
        tell(sender,Text.fill(settings.msg("skin-fetching"),Map.of("stock",settings.stocks().get(stock).name(),"skin",nickname)));
        exchange.updateSkin(stock,nickname,message->tell(sender,message));exchange.render();saveState();writeAdminStatus();
    }
    private Set<String> delisted() { Set<String>s=new HashSet<>();market.quotes.forEach((id,q)->{if(q.delisted)s.add(id);});return s; }
    private void announceDelisted(Set<String> before) { market.quotes.forEach((id,q)->{if(q.delisted&&!before.contains(id))broadcast(settings.msg("delisted").replace("{stock}",q.spec.name()));}); }
    private void fireNews(String id,String stock) {
        try {
            var before=delisted();var bulletin=market.news(id,stock,random);var q=market.quote(bulletin.stockId());
            String headline=bulletin.news().headline().replace("{stock}",q.spec.name()).replace("{종목명}",q.spec.name());
            market.latestHeadline=headline;exchange.refreshNews();
            getLogger().info("[뉴스] "+headline);
            if(settings.ui().getBoolean("news.broadcast-chat"))broadcast(Text.fill(settings.msg("news-chat"),Map.of("headline",headline,"duration",Integer.toString(bulletin.news().duration()))));
            // 타이틀은 화면 폭이 좁아 긴 정식 회사명 대신 짧은 이름(첫 별칭)을 씁니다. 뉴스판·로그·채팅은 정식 이름 그대로입니다.
            String shortName=settings.stockCommandName(q.spec.id());
            String titleLine=bulletin.news().headline().replace("{stock}",shortName).replace("{종목명}",shortName);
            Title title=Title.title(Text.component(settings.msg("news-title")),Text.component(titleLine),Title.Times.times(Duration.ofMillis(300),Duration.ofSeconds(3),Duration.ofMillis(500)));
            if(settings.ui().getBoolean("news.broadcast-title"))for(Player p:Bukkit.getOnlinePlayers())if(!p.hasMetadata("NPC"))p.showTitle(title);
            announceDelisted(before);exchange.render();saveState();
        } catch(IllegalArgumentException e) { if(id!=null)throw e;getLogger().info("자동 뉴스 건너뜀: "+e.getMessage()); }
    }
    void tell(CommandSender sender,String message) { sender.sendMessage(Text.component(settings.msg("prefix")+message)); }
    /** 회수는 시장 시각의 예약 시점마다 딱 한 번. 실제 접속자 목록에 NPC는 넣지 않습니다. */
    private void advanceLoans() {
        if(persistence!=null&&persistence.hasFatalFailure())return;
        var online=new HashSet<UUID>();for(Player p:Bukkit.getOnlinePlayers())if(!p.hasMetadata("NPC"))online.add(p.getUniqueId());
        var events=loans.advance(online);if(events.isEmpty())return;
        saveState();
        for(var event:events) {
            Player p=Bukkit.getPlayer(event.player());if(p==null||p.hasMetadata("NPC"))continue;
            String key=switch(event.kind()) {
                case DUE_SOON->"loan-due-soon";case REPAID->"loan-cleared";case COLLECTED->"loan-collected";case BANKRUPT->"loan-bankrupt";
                case COLLATERAL_SOLD->"loan-collateral-sold";case COLLATERAL_FORFEITED->"loan-collateral-forfeited";
            };
            var values=loanValues(event.player());values.put("amount",Text.money(settings,event.amount()));
            tell(p,Text.fill(settings.msg(key),values));ui.refreshPlayer(p);
            // 만기 경고와 파산은 채팅에 묻히지 않도록 화면 타이틀로도 보여 줍니다.
            if(event.kind()==Loans.Kind.DUE_SOON||event.kind()==Loans.Kind.BANKRUPT)
                p.showTitle(Title.title(Text.component(settings.msg(event.kind()==Loans.Kind.BANKRUPT?"loan-bankrupt-title":"loan-due-soon-title")),
                        Text.component(Text.fill(settings.msg(event.kind()==Loans.Kind.BANKRUPT?"loan-bankrupt-subtitle":"loan-due-soon-subtitle"),values)),
                        Title.Times.times(Duration.ofMillis(200),Duration.ofSeconds(3),Duration.ofMillis(500))));
        }
        exchange.refreshPortfolios();
        if(collectors!=null)collectors.refresh();
    }
    private Map<String,String> loanValues(UUID player) {
        var values=new HashMap<String,String>();var a=market.accounts.get(player);var d=a==null?null:a.debt;var rules=loans.rulesFor(player);
        values.put("principal",Text.money(settings,d==null?0:d.principal));values.put("interest",Text.money(settings,d==null?0:d.interest));
        values.put("debt",Text.money(settings,d==null?0:d.balance()));values.put("seconds",Long.toString(d==null?0:secondsUntil(d.dueAt,market.clockMillis)));
        values.put("cash",Text.money(settings,a==null?0:a.cash));
        values.put("rate",Text.percent(settings,rules.interestPercent()));values.put("interval",Integer.toString(rules.interestIntervalSeconds()));
        values.put("feePercent",Text.percent(settings,rules.upfrontFeePercent()));values.put("maxInterest",Text.percent(settings,rules.maxInterestPercent()));
        values.put("collectorGrace",Integer.toString(settings.ui().getInt("loans.collector.grace-seconds")));
        values.put("rest",Integer.toString(settings.ui().getInt("loans.collector.rest-seconds",60)));
        long nextVisit=CollectorPolicy.graceRemainingMillis(d,market.clockMillis,settings.ui().getInt("loans.collector.grace-seconds")*1000L);
        values.put("collectorSeconds",Long.toString(nextVisit<=0?0:(nextVisit-1)/1000+1));
        values.put("status",settings.msg(d==null?"debt-none":d.bankrupt?"debt-bankrupt":"debt-active"));
        values.put("status",Text.fill(values.get("status"),values));return values;
    }
    /** 추심원에게 잡힌 파산자의 판매 재료 압류. 저장이 실패 중이면 재산을 건드리지 않습니다. */
    void seizeResourcesForDebt(Player player) {
        try{requireFinancialState();workShop.seizeForDebt(player);}
        catch(Exception e){getLogger().warning("추심 압류를 건너뜀: "+e.getMessage());}
    }
    private static long secondsUntil(long deadline,long now){return deadline<=now?0:((deadline-now-1)/1000)+1;}
    /** 송금과 대출 입력은 원 단위 양의 정수. 내부 계산은 정수 전을 유지합니다. */
    private String loanEventName(Loans.Kind kind){return settings.ui().getString("messages.offline-kinds."+kind.name(),kind.name());}
    static long wholeMoney(String input) {
        GameConfig.require(input!=null&&input.matches("[0-9]+"),"금액은 원 단위 양의 정수로 입력하세요");
        long amount=Market.cents(input);GameConfig.require(amount>0,"금액은 0원보다 커야 합니다");return amount;
    }
    private void playerMoneyCommand(Player p,String sub,String[] args) {
        requireFinancialState();
        advanceLoans();
        switch(sub) {
            case "pay" -> {
                GameConfig.require(args.length==3,"/stock pay <접속 중인 닉네임> <금액>");
                Player recipient=Bukkit.getPlayerExact(args[1]);
                GameConfig.require(recipient!=null&&recipient.isOnline()&&!recipient.hasMetadata("NPC"),"접속 중인 플레이어의 정확한 닉네임을 입력하세요");
                long amount=wholeMoney(args[2]);market.transfer(p.getUniqueId(),recipient.getUniqueId(),amount);
                loans.reconcile(recipient.getUniqueId());advanceLoans();collectors.refresh();
                saveState();tell(p,Text.fill(settings.msg("pay-sent"),Map.of("player",recipient.getName(),"amount",Text.money(settings,amount),"cash",Text.money(settings,market.account(p.getUniqueId()).cash))));
                tell(recipient,Text.fill(settings.msg("pay-received"),Map.of("player",p.getName(),"amount",Text.money(settings,amount),"cash",Text.money(settings,market.account(recipient.getUniqueId()).cash))));
                ui.refreshPlayer(p);ui.refreshPlayer(recipient);exchange.refreshPortfolios();
            }
            case "loan" -> {
                GameConfig.require(args.length<=2,"/stock loan [금액]");
                lenderAction(p,"loan",args.length==2?wholeMoney(args[1]):settings.loanRules().defaultPrincipal());
            }
            case "repay" -> {
                GameConfig.require(args.length==2,"/stock repay <금액|all>");
                lenderAction(p,"repay",args[1].equalsIgnoreCase("all")?-1:wholeMoney(args[1]));
            }
            case "debt" -> {GameConfig.require(args.length==1,"/stock debt");tell(p,Text.fill(settings.msg(loans.balance(p.getUniqueId())==0?"loan-none":"loan-status"),loanValues(p.getUniqueId())));}
        }
        writeAdminStatus();
    }
    void lenderAction(Player p,String action,long amount) {
        requireFinancialState();
        advanceLoans();
        if(action.equals("debt")){tell(p,Text.fill(settings.msg(loans.balance(p.getUniqueId())==0?"loan-none":"loan-status"),loanValues(p.getUniqueId())));return;}
        long paid;Loans.Borrowing borrowing=null;
        if(action.equals("loan")){borrowing=loans.borrow(p.getUniqueId(),amount);paid=borrowing.principal();}
        else {
            var repayment=loans.repayResult(p.getUniqueId(),amount);paid=repayment.amount();
            if(repayment.status()==Loans.RepayStatus.NO_DEBT){tell(p,settings.msg("loan-none"));return;}
            if(repayment.status()==Loans.RepayStatus.CLEARED&&paid==0){saveState();advanceLoans();collectors.refresh();tell(p,settings.msg("loan-cleared"));return;}
            if(repayment.status()==Loans.RepayStatus.INSUFFICIENT_CASH){tell(p,settings.msg("loan-no-cash").replace("{debt}",Text.money(settings,repayment.remaining())));saveState();return;}
        }
        saveState();var values=loanValues(p.getUniqueId());values.put("amount",Text.money(settings,paid));
        if(borrowing!=null){values.put("fee",Text.money(settings,borrowing.fee()));values.put("received",Text.money(settings,borrowing.received()));}
        tell(p,Text.fill(settings.msg(action.equals("loan")?"loan-borrowed":"loan-repaid"),values));advanceLoans();collectors.refresh();ui.refreshPlayer(p);writeAdminStatus();
    }
    void openLender(Player p) {
        if(p.hasMetadata("NPC")||p.isDead()||p.getGameMode()==GameMode.SPECTATOR||!clickGate.allow(p.getUniqueId(),Bukkit.getCurrentTick()))return;
        advanceLoans();ui.openLender(p);
    }
    private boolean ownsLender(org.bukkit.entity.Entity entity) {return lender!=null&&lender.owns(entity);}
    private void manageLender(CommandSender sender,String[] args) throws Exception {
        GameConfig.require(args.length>=2,"/stock lender <spawn [닉네임]|remove|skin 닉네임|list>");
        String action=args[1].toLowerCase(Locale.ROOT);
        if(action.equals("settings")){GameConfig.require(sender instanceof Player,"게임 안에서 사용하세요");openLoanAdmin((Player)sender);return;}
        if(action.equals("list")) {
            GameConfig.require(args.length==2,"/stock lender list");
            if(!lenderSettings.enabled()){tell(sender,settings.msg("lender-none"));return;}
            tell(sender,Text.fill(settings.msg("lender-location"),Map.of("world",lenderSettings.world(),"x",Double.toString(lenderSettings.x()),"y",Double.toString(lenderSettings.y()),"z",Double.toString(lenderSettings.z()),"skin",lenderSettings.skinName().isBlank()?"default":lenderSettings.skinName())));return;
        }
        LenderSettings updated;
        if(action.equals("remove")) {GameConfig.require(args.length==2,"/stock lender remove");updated=LenderSettings.disabled();}
        else {
            GameConfig.require(Bukkit.getPluginManager().isPluginEnabled("Citizens"),"사채업자 NPC에는 Citizens가 필요합니다. 서버 재시작으로 최신 의존성을 적용하세요");
            if(action.equals("spawn")) {
                GameConfig.require(sender instanceof Player,"설치할 위치에 서서 게임 안에서 사용하세요");GameConfig.require(args.length<=3,"/stock lender spawn [Minecraft 닉네임]");
                var p=(Player)sender;var loc=p.getLocation();String skin=args.length==3?args[2]:lenderSettings.skinName();
                // Bukkit yaw는 회전 횟수에 따라 범위를 넘어가므로 저장 전에 같은 방향으로 정규화합니다.
                float yaw=(float)(Math.floorMod((long)(loc.getYaw()*1000)+180000,360000)/1000.0-180);
                updated=new LenderSettings(true,loc.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ(),yaw,loc.getPitch(),skin);
            } else {
                GameConfig.require(action.equals("skin")&&args.length==3,"/stock lender <spawn [닉네임]|remove|skin 닉네임|list>");
                GameConfig.require(lenderSettings.enabled(),"먼저 /stock lender spawn 으로 설치하세요");var old=lenderSettings;
                updated=new LenderSettings(true,old.world(),old.x(),old.y(),old.z(),old.yaw(),old.pitch(),args[2]);
            }
        }
        LenderSettings.applyAndSave(getDataFolder(),lenderSettings,updated,lender::apply);lenderSettings=updated;
        tell(sender,settings.msg(action.equals("remove")?"lender-removed":action.equals("skin")?"lender-skin":"lender-spawned").replace("{skin}",updated.skinName()));
    }
    void broadcast(String message) { Bukkit.broadcast(Text.component(settings.msg("prefix")+message)); }
    void saveState() {
        if(!loaded)return;
        persistence.markDirty();
    }
    private void persistenceFailed(Exception error) {
        market.running=false;if(collectors!=null)collectors.refresh();if(ui!=null)ui.closeMenus();
        getLogger().log(java.util.logging.Level.SEVERE,"자산 저장 재시도 실패로 시장 휴장",error);broadcast(settings.msg("save-failed"));
    }
    @Override public void onDisable() {
        tasks.forEach(BukkitTask::cancel);clickGate.clear();if(parkour!=null)parkour.close();if(workArea!=null)workArea.close();if(workShop!=null)workShop.close();if(loanAdmin!=null)loanAdmin.close();if(collectors!=null)collectors.close();if(lender!=null)lender.close();if(ui!=null)ui.close();if(exchange!=null)exchange.close();
        if(persistence!=null)try{persistence.close(market);}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"종료 시 최종 자산 저장 실패",e);}
    }
    boolean inExchange(org.bukkit.entity.Entity e) { return exchange!=null&&exchange.world()!=null&&e.getWorld().equals(exchange.world()); }
    @EventHandler public void join(PlayerJoinEvent e) {
        var p=e.getPlayer();if(p.hasMetadata("NPC"))return;boolean first=!market.accounts.containsKey(p.getUniqueId());market.account(p.getUniqueId()).name=p.getName();resources.purge(p);saveState();tell(p,settings.msg("welcome"));
        var summary=loans.drainOfflineSummary(p.getUniqueId());
        if(!summary.isEmpty()) {
            tell(p,settings.msg("offline-loan-header"));
            summary.forEach((kind,row)->tell(p,Text.fill(settings.msg("offline-loan-line"),Map.of("kind",loanEventName(kind),"count",Long.toString(row.count()),"amount",Text.money(settings,row.amount())))));
            tell(p,Text.fill(settings.msg(loans.balance(p.getUniqueId())==0?"loan-none":"loan-status"),loanValues(p.getUniqueId())));saveState();
        }
        if(first&&settings.rules().teleportFirst())Bukkit.getScheduler().runTaskLater(this,()->{if(p.isOnline()&&exchange.ready())p.teleport(exchange.spawn());},40);
    }
    @EventHandler public void quit(PlayerQuitEvent e) { if(e.getPlayer().hasMetadata("NPC"))return;clickGate.forget(e.getPlayer().getUniqueId());ui.remove(e.getPlayer());exchange.removePlayer(e.getPlayer().getUniqueId());saveState(); }
    @EventHandler(priority=EventPriority.HIGHEST) public void terminalRightClick(PlayerInteractEntityEvent e) {
        if(ownsLender(e.getRightClicked())){e.setCancelled(true);if(e.getHand()==EquipmentSlot.HAND)openLender(e.getPlayer());return;}
        if(!exchange.owns(e.getRightClicked()))return;e.setCancelled(true);
        if(e.getHand()==EquipmentSlot.HAND)terminalTrade(e.getPlayer(),exchange.stock(e.getRightClicked()),false);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void terminalRightClickAt(PlayerInteractAtEntityEvent e) {terminalRightClick(e);}
    /** PvP=false와 NPC 무적 처리보다 먼저 도착합니다. 클릭은 받고 실제 공격은 취소합니다. */
    @EventHandler(priority=EventPriority.HIGHEST) public void terminalAttack(PrePlayerAttackEntityEvent e) {
        if(ownsLender(e.getAttacked())){e.setCancelled(true);openLender(e.getPlayer());return;}
        String stock=exchange.stock(e.getAttacked());
        if(stock==null||!market.quotes.containsKey(stock))return;
        e.setCancelled(true);terminalTrade(e.getPlayer(),stock,true);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void damage(EntityDamageEvent e) {
        if(e instanceof EntityDamageByEntityEvent hit&&collectors!=null&&collectors.owns(hit.getDamager())) {
            if(!collectors.allowsDamage(hit.getDamager(),e.getEntity()))e.setCancelled(true);return;
        }
        if(ownsLender(e.getEntity())){e.setCancelled(true);return;}
        if(!inExchange(e.getEntity()))return;
        if(settings.map().getBoolean("protection.allow-building",true)&&!(e.getEntity() instanceof Player)&&!exchange.owns(e.getEntity())&&(collectors==null||!collectors.owns(e.getEntity())))return;
        e.setCancelled(true);
    }
    @EventHandler public void hunger(FoodLevelChangeEvent e) { if(inExchange(e.getEntity())){e.setCancelled(true);e.getEntity().setFoodLevel(20);} }
    private boolean canBuild(Player player){return player.isOp()||settings.map().getBoolean("protection.allow-building",false);}
    private boolean parkourReserved(org.bukkit.block.Block block){return parkour!=null&&parkour.contains(block);}
    @EventHandler(priority=EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e) {
        if(!inExchange(e.getPlayer()))return;
        boolean mining=workArea!=null&&workArea.canMine(e.getBlock())&&e.getPlayer().getGameMode()==GameMode.SURVIVAL;
        if(exchange.reserved(e.getBlock())||parkourReserved(e.getBlock())||!canBuild(e.getPlayer())&&!mining)e.setCancelled(true);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void mined(BlockBreakEvent e){if(workArea!=null&&workArea.canMine(e.getBlock()))workArea.onMined(e.getBlock());}
    @EventHandler(priority=EventPriority.HIGHEST) public void placeBlock(BlockPlaceEvent e) {if(inExchange(e.getPlayer())&&(!canBuild(e.getPlayer())||exchange.reserved(e.getBlock())||parkourReserved(e.getBlock())||workArea!=null&&workArea.contains(e.getBlock())&&!e.getPlayer().isOp()))e.setCancelled(true);}
    @EventHandler public void bucket(PlayerBucketEmptyEvent e){if(inExchange(e.getPlayer())&&(!canBuild(e.getPlayer())||parkourReserved(e.getBlockClicked().getRelative(e.getBlockFace()))))e.setCancelled(true);}
    @EventHandler public void bucket(PlayerBucketFillEvent e){if(inExchange(e.getPlayer())&&(!canBuild(e.getPlayer())||parkourReserved(e.getBlockClicked().getRelative(e.getBlockFace()))))e.setCancelled(true);}
    @EventHandler public void parkourFlow(BlockFromToEvent e){if(parkourReserved(e.getBlock())||parkourReserved(e.getToBlock()))e.setCancelled(true);}
    @EventHandler public void explode(EntityExplodeEvent e){if(exchange.world()!=null&&e.getLocation().getWorld().equals(exchange.world())){if(!settings.map().getBoolean("protection.allow-building",false))e.blockList().clear();else e.blockList().removeIf(block->exchange.reserved(block)||parkourReserved(block)||workArea!=null&&workArea.contains(block));}}
    @EventHandler public void explode(BlockExplodeEvent e){if(exchange.world()!=null&&e.getBlock().getWorld().equals(exchange.world())){if(!settings.map().getBoolean("protection.allow-building",false))e.blockList().clear();else e.blockList().removeIf(block->exchange.reserved(block)||parkourReserved(block)||workArea!=null&&workArea.contains(block));}}
    private boolean movesReserved(List<org.bukkit.block.Block> blocks,org.bukkit.block.BlockFace direction) {
        return blocks.stream().anyMatch(block->exchange.reserved(block)||exchange.reserved(block.getRelative(direction))||exchange.reserved(block.getRelative(direction.getOppositeFace()))||parkourReserved(block)||parkourReserved(block.getRelative(direction))||parkourReserved(block.getRelative(direction.getOppositeFace()))||workArea!=null&&(workArea.contains(block)||workArea.contains(block.getRelative(direction))));
    }
    @EventHandler public void piston(BlockPistonExtendEvent e) {if(inExchangeBlock(e.getBlock())&&(!settings.map().getBoolean("protection.allow-building",false)||movesReserved(e.getBlocks(),e.getDirection())))e.setCancelled(true);}
    @EventHandler public void piston(BlockPistonRetractEvent e) {if(inExchangeBlock(e.getBlock())&&(!settings.map().getBoolean("protection.allow-building",false)||movesReserved(e.getBlocks(),e.getDirection())))e.setCancelled(true);}
    @EventHandler public void armorStand(PlayerArmorStandManipulateEvent e){if(inExchange(e.getPlayer())&&!canBuild(e.getPlayer()))e.setCancelled(true);}
    @EventHandler public void hanging(org.bukkit.event.hanging.HangingBreakEvent e){if(inExchange(e.getEntity())&&!settings.map().getBoolean("protection.allow-building",false)&&(!(e instanceof org.bukkit.event.hanging.HangingBreakByEntityEvent hit)||!(hit.getRemover() instanceof Player player)||!player.isOp()))e.setCancelled(true);}
    private boolean inExchangeBlock(org.bukkit.block.Block block) {return exchange.world()!=null&&block.getWorld().equals(exchange.world());}
    @EventHandler(priority=EventPriority.HIGHEST) public void interact(PlayerInteractEvent e) {
        if(!inExchange(e.getPlayer())||e.getClickedBlock()==null)return;
        String stock=exchange.stock(e.getClickedBlock());
        if(stock==null) {
            if(!canBuild(e.getPlayer())) {
                if(e.getAction()==org.bukkit.event.block.Action.PHYSICAL)e.setCancelled(true);
                if(e.getAction()==org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK&&e.getMaterial()!=Material.FISHING_ROD)e.setUseItemInHand(Event.Result.DENY);
            }
            return;
        }
        boolean left=e.getAction()==org.bukkit.event.block.Action.LEFT_CLICK_BLOCK;
        if(!left&&e.getAction()!=org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)return;
        e.setCancelled(true);e.setUseInteractedBlock(Event.Result.DENY);e.setUseItemInHand(Event.Result.DENY);
        if(e.getHand()==EquipmentSlot.HAND)terminalTrade(e.getPlayer(),stock,left);
    }
    @EventHandler public void move(PlayerMoveEvent e) {
        if(inExchange(e.getPlayer())&&exchange.ready()&&e.getTo()!=null&&e.getTo().getY()<settings.map().getInt("origin.y")-5)e.setTo(exchange.spawn());
    }
    @EventHandler public void respawn(PlayerRespawnEvent e) { if(inExchange(e.getPlayer())&&exchange.ready())e.setRespawnLocation(exchange.spawn()); }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        try {
            String sub=args.length==0?"menu":args[0].toLowerCase(Locale.ROOT);
            if(sub.equals("parkour")) {
                GameConfig.require(sender instanceof Player,"게임 안에서 사용하세요");
                GameConfig.require(args.length==1||args.length==2&&args[1].equalsIgnoreCase("leave"),"/stock parkour [leave]");
                GameConfig.require(parkour!=null&&parkour.ready(),parkourSettings.text("not-ready"));
                if(args.length==2)parkour.leave((Player)sender);else goParkour((Player)sender);return true;
            }
            if(List.of("work","fish","shop","sell","workkit").contains(sub)) {
                GameConfig.require(sender instanceof Player,"게임 안에서 사용하세요");GameConfig.require(args.length==1,"/stock "+sub);Player p=(Player)sender;
                switch(sub){case "work"->goWork(p,false);case "fish"->goWork(p,true);case "shop"->workShop.open(p);case "sell"->workShop.sell(p,null);case "workkit"->workShop.kit(p);}return true;
            }
            if(List.of("pay","loan","repay","debt").contains(sub)) {
                GameConfig.require(sender instanceof Player,"게임 안에서 사용하세요");var p=(Player)sender;GameConfig.require(!p.hasMetadata("NPC"),"실제 플레이어만 사용할 수 있습니다");
                playerMoneyCommand(p,sub,args);return true;
            }
            if(List.of("menu","join","portfolio").contains(sub)) {
                GameConfig.require(sender instanceof Player,"게임 안에서 사용하세요");var p=(Player)sender;
                switch(sub) {case "menu"->ui.open(p,null,0);case "portfolio"->ui.portfolio(p);case "join"->{GameConfig.require(exchange.ready(),"거래소 건설 중입니다. 잠시 후 다시 시도하세요");p.teleport(exchange.spawn());}}
                return true;
            }
            if(!sender.hasPermission("stock.admin")){tell(sender,settings.msg("no-permission"));return true;}
            switch(sub) {
                case "start" -> {requireFinancialState();market.running=true;saveState();exchange.render();broadcast(settings.msg("started"));}
                case "stop" -> {advanceLoans();market.running=false;collectors.refresh();persistence.flushNow(market);exchange.render();ui.closeMenus();broadcast(settings.msg("stopped"));}
                case "news" -> {
                    GameConfig.require(market.running,"휴장 중에는 뉴스를 실행할 수 없습니다");
                    // 회사 이름에 공백이 있어도 마지막까지 하나의 대상으로 읽습니다.
                    String target=args.length>2?settings.resolveStock(String.join(" ",Arrays.copyOfRange(args,2,args.length))):null;
                    fireNews(args.length>1&&!args[1].equalsIgnoreCase("random")?args[1]:null,target);
                }
                case "setprice" -> {
                    GameConfig.require(args.length>=3,"/stock setprice <회사명> <가격>");
                    // 가격은 마지막 인자이고, 앞부분은 표시 회사명 또는 설정한 명령어 별칭입니다.
                    // 저장 데이터의 내부 ID는 바꾸지 않아 기존 주식과 뉴스 대상이 유지됩니다.
                    String stock=settings.resolveStock(String.join(" ",Arrays.copyOfRange(args,1,args.length-1)));
                    var before=delisted();market.setPrice(stock,Market.cents(args[args.length-1]));announceDelisted(before);saveState();exchange.render();
                    tell(sender,market.quote(stock).spec.name()+" = "+Text.money(settings,market.quote(stock).price));
                }
                case "skin" -> {
                    GameConfig.require(args.length>=3,"/stock skin <회사명> <Minecraft 닉네임>");
                    String stock=settings.resolveStock(String.join(" ",Arrays.copyOfRange(args,1,args.length-1)));
                    changeSkin(sender,stock,args[args.length-1]);
                }
                case "reload" -> reloadSettings(sender);
                case "lender" -> manageLender(sender,args);
                case "loanadmin" -> {GameConfig.require(sender instanceof Player,"게임 안에서 사용하세요");openLoanAdmin((Player)sender);}
                case "interval" -> {GameConfig.require(args.length==2,"/stock interval <초: 0 이상, 소수 가능>");changeInterval(sender,Double.parseDouble(args[1]));}
                case "initialcash" -> {GameConfig.require(args.length==2,"/stock initialcash <기본 지급 금액>");long cash=Market.cents(args[1]);persistSettings(settings.withInitialCash(cash),Map.of("game.initial-cash",java.math.BigDecimal.valueOf(cash,2)));tell(sender,settings.msg("initialcash-changed").replace("{amount}",Text.money(settings,cash)));}
                case "reset" -> {if(args.length!=2||!args[1].equalsIgnoreCase("confirm"))tell(sender,settings.msg("reset-confirm"));else resetGame(sender);}
                case "status" -> {
                    tell(sender,"market="+market.running+", accounts="+market.accounts.size()+", clock="+market.clockMillis+", map="+exchange.ready()+", interval="+settings.rules().tickSeconds());
                    market.quotes.forEach((id,q)->tell(sender,q.spec.name()+" "+Text.money(settings,q.price)+" history="+q.history.size()+" delisted="+q.delisted));
                }
                default -> tell(sender,command.getUsage());
            }
        } catch(Exception e) {tell(sender,settings.msg("error").replace("{reason}",Objects.toString(e.getMessage(),e.getClass().getSimpleName())));}
        return true;
    }
    private void reloadSettings(CommandSender sender) throws Exception {
        GameConfig next=GameConfig.load(getDataFolder());LenderSettings nextLender=LenderSettings.load(getDataFolder());WorkSettings nextWork=WorkSettings.load(getDataFolder());ParkourSettings nextParkour=ParkourSettings.load(getDataFolder());
        workSettings.requireSameLayout(nextWork);
        parkourSettings.requireSameLayout(nextParkour);
        // 맵의 건축 구조는 서버 재시작으로 변경합니다. 실행 중에는 글자/색상만 갱신합니다.
        for(String key:List.of("world-name","origin","layout","chart.width","chart.height","chart.floor-offset","palette",
                "kiosk.columns","kiosk.spacing","kiosk.row-spacing","kiosk.margin","kiosk.time","kiosk.floor",
                "kiosk.pedestal-width","kiosk.pedestal-height","kiosk.history-height","kiosk.name-height","kiosk.line-spacing",
                "kiosk.display-mode","kiosk.npc-height","kiosk.npc-yaw",
                "kiosk.block-height","kiosk.block-scale","kiosk.text-scale","kiosk.name-line-width",
                "kiosk.portfolio-x","kiosk.portfolio-y","kiosk.leaderboard-x","kiosk.leaderboard-y","kiosk.leaderboard-z",
                "kiosk.leaderboard-scale","kiosk.leaderboard-line-width","kiosk.news-offset-x","kiosk.news-offset-y","kiosk.news-offset-z",
                "kiosk.news-scale","kiosk.news-line-width","ranking.world-enabled","ranking.x","ranking.y","ranking.z","ranking.scale","ranking.line-width"))
            GameConfig.require(Objects.equals(settings.map().get(key) instanceof org.bukkit.configuration.ConfigurationSection s?s.getValues(true):settings.map().get(key),next.map().get(key) instanceof org.bukkit.configuration.ConfigurationSection s?s.getValues(true):next.map().get(key)),"맵 구조 수정은 서버 재시작 후 반영: "+key);
        boolean changedStocks=!new ArrayList<>(settings.stocks().keySet()).equals(new ArrayList<>(next.stocks().keySet()));
        if(changedStocks)exchange.preflight(next);
        var previous=settings;var previousWork=workSettings;var previousLender=lenderSettings;var previousParkour=parkourSettings;var snapshot=new MarketReloadSnapshot(market);var before=delisted();
        long previousPriceTime=nextPrice,previousTicks=serverTicks;boolean previousChartDirty=chartDirty;
        boolean rebuilding=false;
        try {
            market.reconfigure(next);settings=next;
            // 예외가 가능한 NPC 적용까지 성공한 뒤에만 나머지 실행 설정을 확정합니다.
            lender.apply(nextLender);
            workShop.reconfigure(nextWork);if(workArea!=null)workArea.reconfigure(nextWork);workSettings=nextWork;
            if(parkour!=null)parkour.reconfigure(nextParkour);parkourSettings=nextParkour;
            ui.closeMenus();loanAdmin.clearMenus();
            if(changedStocks){rebuilding=true;exchange.close();exchange.open();}
            persistence.reconfigure(StatePersistence.Options.from(next.ui()));schedule();lenderSettings=nextLender;collectors.refresh();exchange.render();
            saveState();announceDelisted(before);tell(sender,settings.msg("reloaded"));
            for(String warning:settings.loanTimingWarnings()){getLogger().warning(warning);tell(sender,"&e"+warning);}
        }catch(Exception failure) {
            snapshot.restore(market);settings=previous;workSettings=previousWork;lenderSettings=previousLender;parkourSettings=previousParkour;
            try{lender.apply(previousLender);}catch(Exception rollback){failure.addSuppressed(rollback);}
            try{workShop.reconfigure(previousWork);if(workArea!=null)workArea.reconfigure(previousWork);}catch(Exception rollback){failure.addSuppressed(rollback);}
            try{if(parkour!=null)parkour.reconfigure(previousParkour);}catch(Exception rollback){failure.addSuppressed(rollback);}
            try{if(rebuilding){exchange.close();exchange.open();}else exchange.render();}catch(Exception rollback){failure.addSuppressed(rollback);}
            persistence.reconfigure(StatePersistence.Options.from(previous.ui()));schedule();nextPrice=previousPriceTime;serverTicks=previousTicks;chartDirty=previousChartDirty;collectors.refresh();
            getLogger().log(java.util.logging.Level.WARNING,"reload 실패: 이전 시장 설정을 복원했습니다",failure);
            tell(sender,settings.msg("reloaded-with-rollback").replace("{reason}",Objects.toString(failure.getMessage(),failure.getClass().getSimpleName())));
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        List<String> options=new ArrayList<>();
        if(args.length==1){options.addAll(List.of("menu","join","portfolio","pay","loan","repay","debt","work","fish","shop","sell","workkit","parkour"));if(sender.hasPermission("stock.admin"))options.addAll(List.of("start","stop","news","setprice","skin","lender","loanadmin","interval","initialcash","reset","reload","status"));}
        if(args.length==2&&sender.hasPermission("stock.admin")&&args[0].equalsIgnoreCase("reset"))options.add("confirm");
        if(args.length==2&&args[0].equalsIgnoreCase("parkour"))options.add("leave");
        if(sender.hasPermission("stock.admin")&&args.length==2&&args[0].equalsIgnoreCase("lender"))options.addAll(List.of("spawn","remove","skin","list","settings"));
        if(args.length==2&&args[0].equalsIgnoreCase("pay"))Bukkit.getOnlinePlayers().stream().filter(p->!p.hasMetadata("NPC")&&!p.equals(sender)).map(Player::getName).forEach(options::add);
        if(args.length==2&&args[0].equalsIgnoreCase("repay"))options.add("all");
        // 공백 없는 회사 별칭을 우선 제안합니다. 전체 회사명 직접 입력도 위 파서에서 지원합니다.
        if(sender.hasPermission("stock.admin")&&args.length==2){if(args[0].equalsIgnoreCase("setprice")||args[0].equalsIgnoreCase("skin"))options.addAll(settings.stockCommandNames());if(args[0].equalsIgnoreCase("news")){options.add("random");settings.news().forEach(n->options.add(n.id()));}}
        if(sender.hasPermission("stock.admin")&&args.length==3&&args[0].equalsIgnoreCase("news")) {
            boolean randomNews=args[1].equalsIgnoreCase("random");
            for(var quote:market.quotes.values()) {
                if(!quote.delisted&&settings.news().stream().anyMatch(n->(randomNews?n.weight()>0:n.id().equals(args[1]))&&n.matches(quote.spec)))
                    options.add(settings.stockCommandName(quote.spec.id()));
            }
        }
        if(sender.hasPermission("stock.admin")&&args.length>=3&&args[0].equalsIgnoreCase("skin"))Bukkit.getOnlinePlayers().stream().filter(p->!p.hasMetadata("NPC")).map(Player::getName).forEach(options::add);
        String last=args.length==0?"":args[args.length-1].toLowerCase(Locale.ROOT);return options.stream().filter(s->s.toLowerCase(Locale.ROOT).startsWith(last)).toList();
    }
}
