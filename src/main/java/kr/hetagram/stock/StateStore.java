package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** state.yml은 운영 데이터입니다. 설정 파일과 별도로 저장하며 임시 파일+원자 교체로 반쪽 저장을 막습니다. */
public final class StateStore implements StatePersistence.Storage {
    private final Path path;
    public StateStore(File directory) { path=directory.toPath().resolve("state.yml"); }
    public void load(Market market) throws Exception {
        if(!Files.exists(path))return;
        var y=new YamlConfiguration(); y.load(path.toFile());
        GameConfig.require(y.getInt("schema")==1,"지원하지 않는 state.yml 형식. 기존 파일을 보존합니다");
        GameConfig.require(y.isBoolean("running"),"시장 개장 상태가 없습니다");
        market.running=y.getBoolean("running"); market.clockMillis=integer(y,"clock",0,Long.MAX_VALUE); market.nextNewsMillis=integer(y,"next-news",0,Long.MAX_VALUE);
        market.resetGeneration=y.isSet("reset-generation")?integer(y,"reset-generation",0,Long.MAX_VALUE):0;
        market.latestHeadline=y.getString("latest-headline","");
        var qs=y.getConfigurationSection("quotes");
        GameConfig.require(qs!=null&&!qs.getKeys(false).isEmpty(),"저장된 주가가 없습니다. 기존 파일을 보존합니다");
        if(qs!=null)for(String id:qs.getKeys(false)) {
            if(!market.quotes.containsKey(id))continue;
            var q=market.quote(id); String p="quotes."+id;
            q.price=integer(y,p+".price",0,market.config.rules().maxPrice()); q.previous=integer(y,p+".previous",0,GameConfig.MAX_PRICE_CENTS);
            q.delisted=y.getBoolean(p+".delisted") || q.price<=q.spec.delist();
            if(y.isSet(p+".effect.expires")&&!q.delisted) {
                double rise=GameConfig.number(y,p+".effect.rise",0,1), multiplier=GameConfig.number(y,p+".effect.multiplier",0,20);
                long anchor=y.isSet(p+".effect.anchor")?integer(y,p+".effect.anchor",0,GameConfig.MAX_PRICE_CENTS):q.price;
                double maxChange=y.isSet(p+".effect.max-change")?GameConfig.number(y,p+".effect.max-change",0,1000):market.config.ui().getDouble("news.max-total-change-percent");
                q.effect=new Market.Effect(rise,multiplier,integer(y,p+".effect.expires",0,Long.MAX_VALUE),anchor,maxChange);
            }
            for(String candle:y.getStringList(p+".history")) {
                String[] parts=candle.split(","); GameConfig.require(parts.length==4,"잘못된 캔들 데이터");
                long o=Long.parseLong(parts[0]),h=Long.parseLong(parts[1]),l=Long.parseLong(parts[2]),c=Long.parseLong(parts[3]);
                GameConfig.require(l>=0&&l<=Math.min(o,c)&&h>=Math.max(o,c),"잘못된 OHLC 데이터");
                q.history.addLast(new Market.Candle(o,h,l,c));
            }
            // 구버전에서 상장폐지 후 양수 가격에 멈춘 데이터도 실제 0원으로 표시합니다.
            if(q.delisted&&q.price>0){long last=q.price;q.previous=last;q.price=0;q.history.addLast(new Market.Candle(last,last,0,0));}
            while(q.history.size()>market.config.map().getInt("chart.history-size"))q.history.removeFirst();
        }
        var as=y.getConfigurationSection("accounts");
        if(as!=null)for(String id:as.getKeys(false)) {
            String p="accounts."+id; var a=new Market.Account(integer(y,p+".cash",0,Long.MAX_VALUE)); a.realized=integer(y,p+".realized",Long.MIN_VALUE,Long.MAX_VALUE);
            // 기존 state.yml에는 키가 없으므로 0으로 호환하고, 손상된 값은 임의 복구하지 않습니다.
            a.parkourRewardReadyAt=y.isSet(p+".parkour-reward-ready-at")?integer(y,p+".parkour-reward-ready-at",0,Long.MAX_VALUE):0;
            a.name=y.getString(p+".name","");
            var hs=y.getConfigurationSection(p+".holdings");
            if(hs!=null)for(String stock:hs.getKeys(false)) {
                long qty=integer(hs,stock+".quantity",1,1000000),cost=integer(hs,stock+".cost",0,Long.MAX_VALUE);
                GameConfig.require(market.quotes.containsKey(stock),"저장된 보유 종목이 설정에 없습니다: "+stock);
                a.holdings.put(stock,new Market.Position(qty,cost));
            }
            if(y.isSet(p+".loan"))a.debt=loadDebt(y,p+".loan",market.clockMillis,market.config.loanRules());
            var offline=y.getConfigurationSection(p+".offline-loans");
            GameConfig.require(!y.isSet(p+".offline-loans")||offline!=null,"부재중 대출 요약은 설정 목록이어야 합니다");
            if(offline!=null)for(String key:offline.getKeys(false)) {
                // 단순화 이전 규칙의 요약(연체·미납·페널티 종료)은 더 이상 생기지 않는 사건이라 안내하지 않고 건너뜁니다.
                if(RETIRED_OFFLINE_KINDS.contains(key))continue;
                Loans.Kind kind;
                try {kind=Loans.Kind.valueOf(key.toUpperCase(java.util.Locale.ROOT).replace('-','_'));}
                catch(IllegalArgumentException unknown) {throw new IllegalArgumentException("알 수 없는 부재중 대출 요약: "+key,unknown);}
                long count=integer(offline,key+".count",1,Long.MAX_VALUE),amount=integer(offline,key+".amount",0,Long.MAX_VALUE);
                long lastAt=integer(offline,key+".last-at",0,market.clockMillis);
                GameConfig.require(!a.offlineLoanSummary.containsKey(kind),"중복 부재중 대출 요약: "+key);
                a.offlineLoanSummary.put(kind,new Loans.OfflineSummary(count,amount,lastAt));
            }
            market.accounts.put(UUID.fromString(id),a);
        }
        // 큰 주가를 지원하더라도 계산 한도를 넘는 저장을 임의로 잘라 복구하지 않습니다.
        // 실패하면 호출자가 로드를 중단하며 원본 파일은 보존됩니다.
        for(var account:market.accounts.values()) {
            Math.addExact(account.cash,market.value(account));
            long cost=0;for(var position:account.holdings.values())cost=Math.addExact(cost,position.cost);
        }
    }
    private static long integer(org.bukkit.configuration.ConfigurationSection section,String key,long min,long max) {
        Object raw=section.get(key);GameConfig.require(raw instanceof Number,"저장 데이터의 정수가 잘못됨: "+key);
        long value=new java.math.BigDecimal(raw.toString()).longValueExact();GameConfig.require(value>=min&&value<=max,"저장 데이터 범위 초과: "+key);return value;
    }
    /** 단순화 이전에만 생기던 부재중 요약 키입니다. */
    private static final java.util.Set<String> RETIRED_OFFLINE_KINDS=java.util.Set.of("overdue","missed","penalty-ended","interest-unpaid");
    /**
     * 예전 저장의 연체·미납·유예·페널티 필드는 새 규칙에 없어 읽지 않습니다. 만기가 지난 예전 대출은 다음 회수 때
     * 새 규칙대로 바로 정산(완납 또는 파산)되고, 이미 파산으로 저장된 대출은 파산 상태를 유지합니다.
     */
    private static Loans.Debt loadDebt(YamlConfiguration y,String p,long clock,GameConfig.LoanRules fallback) {
        var debt=new Loans.Debt();
        debt.contract=LoanContract.load(y,p+".contract",fallback);
        debt.originalPrincipal=integer(y,p+".original-principal",1,Long.MAX_VALUE);
        debt.principal=integer(y,p+".principal",0,debt.originalPrincipal);
        debt.interest=integer(y,p+".interest",0,Long.MAX_VALUE);
        debt.totalInterest=integer(y,p+".total-interest",debt.interest,Long.MAX_VALUE);
        debt.borrowedAt=integer(y,p+".borrowed-at",0,clock);
        GameConfig.require(debt.borrowedAt<Long.MAX_VALUE,"대출 시작 시각이 잘못되었습니다");
        debt.dueAt=integer(y,p+".due-at",debt.borrowedAt+1,Long.MAX_VALUE);
        debt.nextInterestAt=integer(y,p+".next-interest-at",debt.borrowedAt+1,Long.MAX_VALUE);
        if(y.isSet(p+".bankrupt"))GameConfig.require(y.isBoolean(p+".bankrupt"),"대출 파산 상태가 잘못되었습니다");
        debt.bankrupt=y.getBoolean(p+".bankrupt",false);
        debt.bankruptAt=y.isSet(p+".bankrupt-at")?integer(y,p+".bankrupt-at",0,clock):(debt.bankrupt?clock:0);
        GameConfig.require(!debt.bankrupt||clock>=debt.dueAt,"만기 전 대출이 파산으로 저장되었습니다");
        debt.collectorRestUntil=y.isSet(p+".collector-rest-until")?integer(y,p+".collector-rest-until",0,Long.MAX_VALUE):0;
        if(y.isSet(p+".due-warned"))GameConfig.require(y.isBoolean(p+".due-warned"),"대출 만기 경고 상태가 잘못되었습니다");
        debt.dueWarned=y.getBoolean(p+".due-warned",false);
        GameConfig.require(debt.balance()>0,"잔액이 없는 대출이 저장되었습니다");
        return debt;
    }
    /** 호출자는 메인 스레드에서만 읽습니다. 반환 문자열에는 가변 Market/Bukkit 참조가 없습니다. */
    public String capture(Market market) {
        var y=new YamlConfiguration(); y.set("schema",1); y.set("running",market.running);
        y.set("clock",market.clockMillis); y.set("next-news",market.nextNewsMillis);
        y.set("reset-generation",market.resetGeneration);y.set("latest-headline",market.latestHeadline);
        market.quotes.forEach((id,q)->{
            String p="quotes."+id; y.set(p+".price",q.price); y.set(p+".previous",q.previous); y.set(p+".delisted",q.delisted);
            y.set(p+".history",q.history.stream().map(c->c.open()+","+c.high()+","+c.low()+","+c.close()).toList());
            if(q.effect!=null) {
                y.set(p+".effect.rise",q.effect.rise());y.set(p+".effect.multiplier",q.effect.multiplier());y.set(p+".effect.expires",q.effect.expires());
                y.set(p+".effect.anchor",q.effect.anchor());y.set(p+".effect.max-change",q.effect.maxChange());
            }
        });
        market.accounts.forEach((id,a)->{
            String p="accounts."+id; y.set(p+".cash",a.cash); y.set(p+".realized",a.realized);
            GameConfig.require(a.parkourRewardReadyAt>=0,"점프맵 보상 대기 시각이 잘못되었습니다");
            y.set(p+".parkour-reward-ready-at",a.parkourRewardReadyAt);
            y.set(p+".name",a.name);
            a.holdings.forEach((s,h)->{y.set(p+".holdings."+s+".quantity",h.quantity);y.set(p+".holdings."+s+".cost",h.cost);});
            a.offlineLoanSummary.forEach((kind,summary)->{
                String key=p+".offline-loans."+kind.name().toLowerCase(java.util.Locale.ROOT).replace('_','-');
                y.set(key+".count",summary.count());y.set(key+".amount",summary.amount());y.set(key+".last-at",summary.lastAt());
            });
            if(a.debt!=null) {
                var d=a.debt;String key=p+".loan";
                (d.contract==null?LoanContract.from(market.config.loanRules()):d.contract).save(y,key+".contract");
                y.set(key+".original-principal",d.originalPrincipal);y.set(key+".principal",d.principal);
                y.set(key+".interest",d.interest);y.set(key+".total-interest",d.totalInterest);
                y.set(key+".borrowed-at",d.borrowedAt);y.set(key+".due-at",d.dueAt);y.set(key+".next-interest-at",d.nextInterestAt);
                y.set(key+".bankrupt",d.bankrupt);y.set(key+".bankrupt-at",d.bankruptAt);
                y.set(key+".collector-rest-until",d.collectorRestUntil);y.set(key+".due-warned",d.dueWarned);
            }
        });
        return y.saveToString();
    }
    /** 기존 동기 단위 API. 운영 플러그인의 일상 저장은 StatePersistence를 통합니다. */
    public void save(Market market) throws Exception {writeSnapshot(capture(market));}
    @Override public void writeSnapshot(String snapshot) throws Exception {atomicWrite(path,snapshot,true);}
    @Override public void writeStatus(String snapshot) throws Exception {atomicWrite(path.resolveSibling("admin-status.json"),snapshot,false);}
    private static void atomicWrite(Path target,String snapshot,boolean keepPrevious) throws Exception {
        Files.createDirectories(target.getParent());Path temporary=target.resolveSibling(target.getFileName()+".tmp");
        try {
            byte[] bytes=snapshot.getBytes(StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
                var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            if(keepPrevious&&Files.exists(target)) {
                Path backup=target.resolveSibling(target.getFileName()+".bak"),backupTemporary=backup.resolveSibling(backup.getFileName()+".tmp");
                try {Files.copy(target,backupTemporary,StandardCopyOption.REPLACE_EXISTING);atomicMove(backupTemporary,backup);}
                finally {Files.deleteIfExists(backupTemporary);}
            }
            atomicMove(temporary,target);
        } finally {Files.deleteIfExists(temporary);}
    }
    private static void atomicMove(Path source,Path target) throws Exception {
        try {Files.move(source,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException unsupported) {Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    /** 단일 저장 worker에서만 호출합니다. 주기 백업과 초기화 전 백업을 분리합니다. */
    @Override public Path backupCurrentState(String purpose,int retention) throws Exception {
        GameConfig.require(purpose.equals("periodic")||purpose.equals("before-reset"),"알 수 없는 상태 백업 종류");
        Path folder=path.getParent().resolve("backups");if(purpose.equals("periodic"))folder=folder.resolve("periodic");Files.createDirectories(folder);
        String timestamp=java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS").withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
        Path backup=folder.resolve((purpose.equals("periodic")?"state":purpose)+"-"+timestamp+"-"+UUID.randomUUID()+".yml"),temporary=backup.resolveSibling(backup.getFileName()+".tmp");
        try {Files.copy(path,temporary);atomicMove(temporary,backup);}finally {Files.deleteIfExists(temporary);}
        if(purpose.equals("periodic")&&retention>0)try(var files=Files.list(folder)) {
            var candidates=files.filter(file->file.getFileName().toString().startsWith("state-")&&file.getFileName().toString().endsWith(".yml")).toList();
            var modified=new java.util.HashMap<Path,Long>();for(Path file:candidates)modified.put(file,Files.getLastModifiedTime(file).toMillis());
            var sorted=candidates.stream().sorted(java.util.Comparator.comparing((Path file)->!file.equals(backup))
                    .thenComparing(java.util.Comparator.comparingLong((Path file)->modified.get(file)).reversed())
                    .thenComparing(java.util.Comparator.comparing((Path file)->file.getFileName().toString()).reversed())).toList();
            for(int index=retention;index<sorted.size();index++)Files.deleteIfExists(sorted.get(index));
        }
        return backup;
    }
    public Path backupBeforeReset(Market market) throws Exception {
        save(market);return backupCurrentState("before-reset",0);
    }
}
