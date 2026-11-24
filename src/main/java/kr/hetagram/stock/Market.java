package kr.hetagram.stock;

import java.math.*;
import java.util.*;
import java.util.random.RandomGenerator;

/** Bukkit과 분리된 거래 엔진. 모든 금액은 정수 전(1원=100전), 모든 메서드는 서버 스레드에서만 호출합니다. */
public final class Market {
    public static final class Position {
        public long quantity, cost;
        public Position(long quantity,long cost) { this.quantity=quantity; this.cost=cost; }
        public long average() { return quantity==0?0:BigDecimal.valueOf(cost).divide(BigDecimal.valueOf(quantity),0,RoundingMode.HALF_UP).longValueExact(); }
    }
    public static final class Account {
        public long cash, realized;
        /** 점프맵 다음 보상 가능 시각(UTC epoch 밀리초). 서버 재시작/재접속으로 대기 시간을 우회하지 않습니다. */
        public long parkourRewardReadyAt;
        public String name="";
        public Loans.Debt debt;
        public final EnumMap<Loans.Kind,Loans.OfflineSummary> offlineLoanSummary=new EnumMap<>(Loans.Kind.class);
        public final Map<String,Position> holdings = new LinkedHashMap<>();
        public Account(long cash) { this.cash=cash; }
    }
    public record Candle(long open,long high,long low,long close) {}
    public record Effect(double rise,double multiplier,long expires,long anchor,double maxChange) {
        /** 직접 지정한 기존 테스트/관리용 효과는 anchor=0으로 누적 뉴스 범위를 사용하지 않습니다. */
        public Effect(double rise,double multiplier,long expires) { this(rise,multiplier,expires,0,0); }
    }
    public static final class Quote {
        public GameConfig.Stock spec;
        public long price, previous;
        public boolean delisted;
        public Effect effect;
        public final Deque<Candle> history=new ArrayDeque<>();
        /** 등락률 기준가(최근 ui.change-window-seconds 전 가격). 0이면 직전 갱신가와 비교합니다. */
        long changeBase;
        final ArrayDeque<long[]> changeSamples=new ArrayDeque<>();
        Quote(GameConfig.Stock spec) { this.spec=spec; price=previous=spec.start(); }
        public double change() { long base=changeBase>0?changeBase:previous; return base==0?0:100.0*(price-base)/base; }
    }
    public record Trade(long quantity,long amount) {}
    public record Bulletin(GameConfig.News news,String stockId,boolean reversed) {}
    public record Rank(UUID player,String name,long money) {}
    public GameConfig config;
    public final LinkedHashMap<String,Quote> quotes=new LinkedHashMap<>();
    public final Map<UUID,Account> accounts=new LinkedHashMap<>();
    public boolean running;
    // 활성 시장 경과 시간. 휴장/서버 종료 동안 뉴스 효과와 자동 뉴스 시계가 정지합니다.
    public long clockMillis, nextNewsMillis;
    public long resetGeneration;
    public String latestHeadline="";
    public Market(GameConfig config) { this.config=config; config.stocks().forEach((id,s)->quotes.put(id,new Quote(s))); running=config.rules().autoStart(); }
    public static long cents(String value) { return new BigDecimal(value).setScale(2,RoundingMode.UNNECESSARY).movePointRight(2).longValueExact(); }
    public static String amount(long cents) { return BigDecimal.valueOf(cents,2).toPlainString(); }
    public Account account(UUID id) { return accounts.computeIfAbsent(id,k->new Account(config.rules().initialCash())); }
    /** 모든 검사를 마친 뒤에만 양쪽 잔고를 바꾸므로 송금 실패로 일부만 반영되지 않습니다. */
    public void transfer(UUID sender,UUID recipient,long amount) {
        GameConfig.require(amount>0,"송금 금액은 양수여야 합니다");
        GameConfig.require(!sender.equals(recipient),"자기 자신에게 송금할 수 없습니다");
        var from=accounts.getOrDefault(sender,new Account(config.rules().initialCash()));
        var to=accounts.getOrDefault(recipient,new Account(config.rules().initialCash()));
        GameConfig.require(!config.ui().getBoolean("loans.block-transfers-while-indebted",true)||from.debt==null||from.debt.balance()==0,"대출이 남아 있는 동안에는 송금할 수 없습니다");
        GameConfig.require(from.debt==null||!from.debt.bankrupt,"파산 중에는 송금할 수 없습니다");
        GameConfig.require(from.cash>=amount,"보유 현금이 부족합니다");
        long fromCash=from.cash-amount,toCash=Math.addExact(to.cash,amount);
        Math.addExact(fromCash,value(from));Math.addExact(toCash,value(to));
        from.cash=fromCash;to.cash=toCash;accounts.put(sender,from);accounts.put(recipient,to);
    }
    /** 설정은 유지하고 게임 진행만 초기화합니다. 호출 전 백업은 서버 서비스에서 수행합니다. */
    public void reset() {
        long generation=Math.addExact(resetGeneration,1);
        accounts.clear();quotes.clear();config.stocks().forEach((id,s)->quotes.put(id,new Quote(s)));
        running=false;clockMillis=0;nextNewsMillis=0;latestHeadline="";resetGeneration=generation;
    }
    public List<Rank> rankings(String metric,int limit) {
        GameConfig.require(List.of("cash","equity","net-cash","net-equity").contains(metric),"순위 기준은 cash/equity/net-cash/net-equity입니다");
        GameConfig.require(limit>0,"순위 인원은 양수여야 합니다");
        return accounts.entrySet().stream().map(e->{var a=e.getValue();return new Rank(e.getKey(),a.name.isBlank()?e.getKey().toString().substring(0,8):a.name,
                        rankValue(a,metric));})
                .sorted(Comparator.comparingLong(Rank::money).reversed().thenComparing(Rank::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Rank::player))
                .limit(limit).toList();
    }
    public long rankValue(Account account,String metric) {
        GameConfig.require(List.of("cash","equity","net-cash","net-equity").contains(metric),"잘못된 자산 기준: "+metric);
        long gross=metric.endsWith("cash")?account.cash:Math.addExact(account.cash,value(account));
        return metric.startsWith("net-")?Math.subtractExact(gross,account.debt==null?0:account.debt.balance()):gross;
    }
    public Quote quote(String id) { var q=quotes.get(id); if(q==null) throw new IllegalArgumentException("알 수 없는 종목: "+id); return q; }
    public void reconfigure(GameConfig next) {
        // 보유 중인 종목 제거는 거부합니다. reload는 자산 초기화 명령이 아닙니다.
        for(var a:accounts.values()) for(var e:a.holdings.entrySet())
            GameConfig.require(e.getValue().quantity==0 || next.stocks().containsKey(e.getKey()),"보유 중인 종목은 삭제할 수 없습니다: "+e.getKey());
        for(var e:quotes.entrySet()) if(next.stocks().containsKey(e.getKey()))
            GameConfig.require(e.getValue().price<=next.rules().maxPrice(),"현재가보다 낮은 max-price는 적용할 수 없습니다");
        var updated=new LinkedHashMap<String,Quote>();
        next.stocks().forEach((id,s)->{
            var q=quotes.getOrDefault(id,new Quote(s));q.spec=s;
            // 폐지 기준을 올리는 reload도 같은 0원 처리 경로를 사용합니다.
            // 기준을 다시 낮추더라도 이미 폐지된 회사는 setprice/reset 전까지 폐지 상태입니다.
            if(q.delisted||q.price<=s.delist()) {
                if(q.price>0)move(q,0,0,0);
                else {q.delisted=true;q.effect=null;}
            }
            updated.put(id,q);
        });
        quotes.clear(); quotes.putAll(updated); config=next;
    }
    private long fee(long amount,double rate) { return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(rate)).setScale(0,RoundingMode.HALF_UP).longValueExact(); }
    private static final BigInteger MAX_MONEY=BigInteger.valueOf(Long.MAX_VALUE);
    private BigInteger totalCost(Account account) {
        BigInteger total=BigInteger.ZERO;
        for(var position:account.holdings.values())total=total.add(BigInteger.valueOf(position.cost));
        return total;
    }
    private BigInteger purchaseCost(long price,long quantity) {
        BigInteger base=BigInteger.valueOf(price).multiply(BigInteger.valueOf(quantity));
        BigInteger fee=new BigDecimal(base).multiply(BigDecimal.valueOf(config.rules().buyFee())).setScale(0,RoundingMode.HALF_UP).toBigIntegerExact();
        return base.add(fee);
    }
    private void tradable(Quote q) {
        if(!running && !config.rules().tradeStopped()) throw new IllegalArgumentException(config.msg("closed"));
        if(q.delisted) throw new IllegalArgumentException(config.msg("state-delisted"));
    }
    public long maxBuy(Account a,Quote q) {
        long owned=a.holdings.getOrDefault(q.spec.id(),new Position(0,0)).quantity;
        BigInteger costBudget=MAX_MONEY.subtract(totalCost(a));
        GameConfig.require(costBudget.signum()>=0,"보유 주식 원가가 저장 가능한 금액 한도를 초과했습니다");
        BigInteger budget=BigInteger.valueOf(a.cash).min(costBudget);
        long low=0,high=Math.max(0,config.rules().maxShares()-owned);
        // 탐색 중 후보 수량은 실제 잔고보다 훨씬 클 수 있으므로 중간 곱도 큰 정수로 비교합니다.
        while(low<high) { long mid=low+(high-low+1)/2;
            if(purchaseCost(q.price,mid).compareTo(budget)<=0)low=mid; else high=mid-1; }
        return low;
    }
    public Trade buy(UUID player,String id,long quantity) {
        var q=quote(id); tradable(q); var a=account(player);
        GameConfig.require(!Loans.blocked(a,true),"연체 페널티가 끝나거나 대출을 모두 갚아야 매수할 수 있습니다");
        if(quantity==-1) quantity=maxBuy(a,q);
        GameConfig.require(quantity>0 && quantity<=config.rules().maxShares(),"매수 가능한 수량이 없습니다");
        var pos=a.holdings.getOrDefault(id,new Position(0,0));
        GameConfig.require(quantity<=config.rules().maxShares()-pos.quantity,"종목별 최대 보유량 초과");
        BigInteger requested=purchaseCost(q.price,quantity);
        GameConfig.require(requested.compareTo(BigInteger.valueOf(a.cash))<=0,"보유 현금이 부족합니다");
        GameConfig.require(totalCost(a).add(requested).compareTo(MAX_MONEY)<=0,"보유 주식 원가가 저장 가능한 금액 한도를 초과합니다");
        long total=requested.longValueExact();
        long cost=Math.addExact(pos.cost,total); a.cash-=total; pos.quantity+=quantity; pos.cost=cost; a.holdings.put(id,pos);
        return new Trade(quantity,total);
    }
    public Trade sell(UUID player,String id,long quantity) {
        var q=quote(id); tradable(q); var a=account(player); var p=a.holdings.get(id);
        GameConfig.require(!Loans.blocked(a,false),"감옥 페널티가 끝나거나 대출을 모두 갚아야 매도할 수 있습니다");
        GameConfig.require(p!=null && p.quantity>0,"보유한 주식이 없습니다");
        if(quantity==-1)quantity=p.quantity;
        return settleSell(a,id,quantity,q.price);
    }
    /** 사용자 매도와 담보 정산이 같은 수수료·평단 원가·실현손익 결제를 사용합니다. */
    private Trade settleSell(Account a,String id,long quantity,long price) {
        var p=a.holdings.get(id);
        GameConfig.require(p!=null&&p.quantity>0,"보유한 주식이 없습니다");
        GameConfig.require(quantity>0 && quantity<=p.quantity,"매도할 수량이 부족합니다");
        long net=saleNet(price,quantity);
        long removed=quantity==p.quantity?p.cost:BigDecimal.valueOf(p.cost).multiply(BigDecimal.valueOf(quantity)).divide(BigDecimal.valueOf(p.quantity),0,RoundingMode.HALF_UP).longValueExact();
        long cash=Math.addExact(a.cash,net),realized=Math.addExact(a.realized,net-removed);
        a.cash=cash; a.realized=realized; p.cost-=removed; p.quantity-=quantity; if(p.quantity==0)a.holdings.remove(id);
        return new Trade(quantity,net);
    }
    private long saleNet(long price,long quantity) {
        long base=Math.multiplyExact(price,quantity);return base-fee(base,config.rules().sellFee());
    }
    private static Account settlementCopy(Account account) {
        var copy=new Account(account.cash);copy.realized=account.realized;
        account.holdings.forEach((id,p)->copy.holdings.put(id,new Position(p.quantity,p.cost)));return copy;
    }
    /** 현금 목표까지 필요한 최소 주수만 회수합니다. 휴장·개인 거래 잠금은 채무 정산을 막지 않습니다. */
    long liquidateCollateral(Account account,long targetCash) {
        if(account.cash>=targetCash)return 0;
        var trial=settlementCopy(account);var plan=new LinkedHashMap<String,Long>();
        for(var entry:account.holdings.entrySet()) {
            var q=quote(entry.getKey());if(q.delisted||q.price<=0||entry.getValue().quantity<=0)continue;
            long needed=targetCash-trial.cash,quantity=entry.getValue().quantity;
            if(saleNet(q.price,quantity)>=needed) {
                long low=1,high=quantity;
                while(low<high) {long mid=low+(high-low)/2;if(saleNet(q.price,mid)>=needed)high=mid;else low=mid+1;}
                quantity=low;
            }
            settleSell(trial,entry.getKey(),quantity,q.price);plan.put(entry.getKey(),quantity);
            if(trial.cash>=targetCash)break;
        }
        // 원계정을 바꾸기 전에 여러 종목의 누적 현금·손익 범위까지 모두 검증합니다.
        long before=account.cash;
        plan.forEach((id,quantity)->settleSell(account,id,quantity,quote(id).price));
        return account.cash-before;
    }
    void validateCollateralSettlement(Account account,long targetCash,boolean alwaysForfeit) {
        var trial=settlementCopy(account);liquidateCollateral(trial,targetCash);
        if(alwaysForfeit||trial.cash<targetCash)forfeitCollateral(trial);
    }
    /** 실제 파산 확정 때 가치 없는 잔존 주식도 원가 손실로 정리하여 사망 후 복원되지 않게 합니다. */
    Trade forfeitCollateral(Account account) {
        var trial=settlementCopy(account);long quantity=0,cost=0;
        for(var entry:account.holdings.entrySet()) {
            quantity=Math.addExact(quantity,entry.getValue().quantity);cost=Math.addExact(cost,entry.getValue().cost);
            settleSell(trial,entry.getKey(),entry.getValue().quantity,0);
        }
        for(var id:new ArrayList<>(account.holdings.keySet()))settleSell(account,id,account.holdings.get(id).quantity,0);
        return new Trade(quantity,cost);
    }
    public long value(Account a) { long total=0; for(var e:a.holdings.entrySet()) { var q=quotes.get(e.getKey()); if(q!=null&&!q.delisted)total=Math.addExact(total,Math.multiplyExact(q.price,e.getValue().quantity)); } return total; }
    public long profit(Account a) { return BigInteger.valueOf(value(a)).subtract(totalCost(a)).longValueExact(); }
    public long positionValue(String id,Position p) { var q=quote(id); return q.delisted?0:Math.multiplyExact(q.price,p.quantity); }
    public static double returns(long value,long cost) { return cost==0?0:100.0*(value-cost)/cost; }
    public void setPrice(String id,long price) {
        GameConfig.require(price>0 && price<=config.rules().maxPrice(),"가격은 0원 초과이며 max-price 이하여야 합니다");
        var q=quote(id);long limit=safePriceLimit(q);
        GameConfig.require(price<=limit,"계정 자산 한도로 적용 가능한 최고가는 "+BigDecimal.valueOf(limit,2).stripTrailingZeros().toPlainString()+"원입니다");
        move(q,price,price,price);q.effect=null;
    }
    /** 가격 상승 후에도 모든 보유자의 현금+전체 주식 평가액이 long 범위 안에 있도록 계산합니다. */
    private long safePriceLimit(Quote changing) {
        BigInteger limit=BigInteger.valueOf(config.rules().maxPrice());
        for(var account:accounts.values()) {
            var position=account.holdings.get(changing.spec.id());
            if(position==null||position.quantity<=0)continue;
            BigInteger other=BigInteger.valueOf(account.cash);
            for(var entry:account.holdings.entrySet()) {
                if(entry.getKey().equals(changing.spec.id()))continue;
                var quote=quotes.get(entry.getKey());
                if(quote!=null&&!quote.delisted)other=other.add(BigInteger.valueOf(quote.price).multiply(BigInteger.valueOf(entry.getValue().quantity)));
            }
            BigInteger quantity=BigInteger.valueOf(position.quantity);
            BigInteger current=other.add(changing.delisted?BigInteger.ZERO:BigInteger.valueOf(changing.price).multiply(quantity));
            // 잘못된 기존 자산을 0원/상장폐지로 바꿔 숨기지 않습니다. 저장 데이터 검증과 함께 실패를 드러냅니다.
            GameConfig.require(current.compareTo(MAX_MONEY)<=0,"기존 계정 자산이 저장 가능한 금액 한도를 초과했습니다");
            limit=limit.min(MAX_MONEY.subtract(other).divide(quantity));
        }
        return limit.max(BigInteger.ZERO).longValueExact();
    }
    private void move(Quote q,long price,long high,long low) {
        long limit=safePriceLimit(q);price=Math.min(price,limit);high=Math.min(high,limit);low=Math.min(low,limit);
        long open=q.price; q.previous=open; q.delisted=price<=q.spec.delist(); q.price=q.delisted?0:price;
        price=q.price;low=Math.min(low,price);
        q.history.addLast(new Candle(open,Math.max(Math.max(open,price),high),Math.min(Math.min(open,price),low),price));
        while(q.history.size()>config.map().getInt("chart.history-size"))q.history.removeFirst();
        if(q.delisted)q.effect=null;
        recordChange(q,open);
    }
    /** 직전 1틱이 아니라 최근 ui.change-window-seconds 동안의 변화를 보여 주도록 초 단위 가격 표본을 유지합니다. */
    private void recordChange(Quote q,long before) {
        int windowSeconds=config.ui().getInt("ui.change-window-seconds",60);
        if(windowSeconds<=0){q.changeBase=0;q.changeSamples.clear();return;}
        long second=Math.floorDiv(clockMillis,1000);
        if(q.changeSamples.isEmpty())q.changeSamples.addLast(new long[]{second-1,before});
        var last=q.changeSamples.peekLast();
        if(last[0]<second)q.changeSamples.addLast(new long[]{second,q.price});else last[1]=q.price;
        // 기준 시각 이전의 가장 최근 표본 하나만 남기고 그 가격을 기준가로 씁니다. 창이 덜 찼으면 가장 오래된 표본입니다.
        while(q.changeSamples.size()>1) {
            var first=q.changeSamples.pollFirst();
            if(q.changeSamples.peekFirst()[0]>second-windowSeconds){q.changeSamples.addFirst(first);break;}
        }
        q.changeBase=q.changeSamples.peekFirst()[1];
    }
    /** factor 배로 바꾼 가격. 정수 전 반올림으로 움직임이 사라져 소액 종목이 멈추지 않게 최소 1전씩 움직입니다. */
    private long scaled(long price,double factor) {
        long value=BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(factor)).setScale(0,RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO).min(BigDecimal.valueOf(config.rules().maxPrice())).longValueExact();
        if(factor<1&&value>=price)value=Math.max(0,price-1);
        if(factor>1&&value<=price&&price<config.rules().maxPrice())value=price+1;
        return value;
    }
    /** 뉴스 즉시 변동 p%: 오를 때는 ×(1+p), 내릴 때는 ÷(1+p)라서 같은 크기의 호재와 악재가 서로 상쇄됩니다. */
    private static double instantFactor(double percent) {
        double factor=1+Math.abs(percent)/100;return percent<0?1/factor:factor;
    }
    private void moveWithNews(Quote quote,long price) {
        var effect=quote.effect;boolean finished=false;
        if(effect!=null&&effect.anchor()>0&&price>quote.spec.delist()) {
            BigDecimal anchor=BigDecimal.valueOf(effect.anchor()),factor=BigDecimal.ONE.add(BigDecimal.valueOf(effect.maxChange()).movePointLeft(2));
            // 상한은 기준가×(1+x), 하한은 기준가÷(1+x)라서 +x% 뒤 같은 크기의 악재를 맞으면 제자리로 돌아옵니다.
            long lower=anchor.divide(factor,0,RoundingMode.CEILING).max(BigDecimal.ZERO).longValueExact();
            long upper=anchor.multiply(factor).setScale(0,RoundingMode.FLOOR).min(new BigDecimal(MAX_MONEY)).longValueExact();
            if(price<=lower){price=lower;finished=true;}
            else if(price>=upper){price=upper;finished=true;}
        }
        // 상장폐지 가격에 도달한 경우에는 뉴스 범위가 상장폐지를 막지 않습니다.
        long open=quote.price;move(quote,price,Math.max(open,price),Math.min(open,price));
        if(finished)quote.effect=null;
    }
    public void tick(RandomGenerator random) {
        if(!running)return;
        double max=config.ui().getDouble("game.max-step-change-percent",8),scale=config.ui().getDouble("game.volatility-scale",1);
        double k=GameConfig.intervalMillis(config.rules().tickSeconds())/1000.0/config.ui().getDouble("game.volatility-reference-seconds",4);
        for(var q:quotes.values()) {
            if(q.delisted)continue;
            if(q.effect!=null && q.effect.expires()<=clockMillis)q.effect=null;
            double chance=q.effect==null?q.spec.riseChance():q.effect.rise();
            double multiplier=(q.effect==null?1:q.effect.multiplier())*scale;
            double lo=q.spec.minChange()*multiplier,hi=q.spec.maxChange()*multiplier;
            double magnitude=lo+random.nextDouble()*(hi-lo);
            double step=random.nextDouble()<chance?magnitude:-magnitude,mean=(2*chance-1)*(lo+hi)/2;
            // 기준 시간(기본 4초) 동안의 평균 추세와 흔들림 크기를 갱신 간격과 무관하게 유지합니다.
            // 추세는 간격에 비례하고 무작위 흔들림은 √간격에 비례하므로 관리자 창의 간격은 부드러움만 바꿉니다.
            // 로그 수익률이라 같은 폭의 상승·하락이 상쇄되어 시간이 지날수록 저절로 내려가는 쏠림이 없습니다.
            double logPercent=Math.max(-max,Math.min(max,mean*k+(step-mean)*Math.sqrt(k)));
            moveWithNews(q,scaled(q.price,Math.exp(logPercent/100)));
        }
    }
    public Bulletin news(String forced,String target,RandomGenerator random) {
        List<GameConfig.News> eligible=config.news().stream().filter(n->(forced==null?n.weight()>0:n.id().equals(forced)))
                .filter(n->quotes.values().stream().anyMatch(q->!q.delisted&&n.matches(q.spec)&&(target==null||q.spec.id().equals(target)))).toList();
        GameConfig.require(!eligible.isEmpty(),"실행 가능한 뉴스/대상 종목이 없습니다");
        GameConfig.News selected;
        if(forced!=null) selected=eligible.getFirst();
        else { double roll=random.nextDouble()*eligible.stream().mapToDouble(GameConfig.News::weight).sum(); selected=eligible.getLast();
            for(var n:eligible){roll-=n.weight();if(roll<0){selected=n;break;}} }
        GameConfig.News chosen=selected;
        List<Quote> targets=quotes.values().stream().filter(q->!q.delisted&&chosen.matches(q.spec)&&(target==null||q.spec.id().equals(target))).toList();
        Quote q=targets.get(random.nextInt(targets.size())); boolean reversed=random.nextDouble()<config.rules().reversal();
        double maxInstant=config.ui().getDouble("news.max-instant-change-percent",25);
        double immediate=Math.max(-maxInstant,Math.min(maxInstant,chosen.immediate()*config.ui().getDouble("news.instant-scale",1)*(reversed?-1:1)));
        double maxTotal=config.ui().getDouble("news.max-total-change-percent",30);
        q.effect=maxTotal>0?new Effect(reversed?1-chosen.riseChance():chosen.riseChance(),chosen.multiplier(),clockMillis+chosen.duration()*1000L,q.price,maxTotal):null;
        // 즉시 변동은 발표 순간 한 번만 적용하며, 지속 변동의 기준 가격에도 이 발표 전 가격을 사용합니다.
        if(q.effect!=null&&immediate!=0)moveWithNews(q,scaled(q.price,instantFactor(immediate)));
        return new Bulletin(chosen,q.spec.id(),reversed);
    }
}
