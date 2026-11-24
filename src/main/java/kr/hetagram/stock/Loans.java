package kr.hetagram.stock;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

/**
 * 만기 일시상환형 사채입니다. 시계는 서버 실시간이 아니라 Market.clockMillis(개장 중 시장 시간)이며 Bukkit을 호출하지 않습니다.
 * 빌릴 때 선이자를 떼고 지급하고, 이자는 간격마다 쌓이기만 하다가 만기에 원리금을 한 번에 회수합니다.
 * 현금이 모자라면 필요한 만큼 주식을 강제 처분하고, 그래도 모자라면 즉시 파산합니다. 파산 뒤에도 이자는 총이자 상한까지 붙습니다.
 */
public final class Loans {
    public static final class Debt {
        public long originalPrincipal,principal,interest,totalInterest,borrowedAt,dueAt,nextInterestAt,bankruptAt,collectorRestUntil;
        public boolean bankrupt,dueWarned;
        public LoanContract contract;
        public long balance() { return Math.addExact(principal,interest); }
    }
    public enum Kind { DUE_SOON,REPAID,COLLECTED,COLLATERAL_SOLD,COLLATERAL_FORFEITED,BANKRUPT }
    public record Event(UUID player,Kind kind,long amount) {}
    public record OfflineSummary(long count,long amount,long lastAt) {}
    /** principal은 갚아야 할 원금, received는 선이자를 떼고 실제로 받은 현금입니다. */
    public record Borrowing(long principal,long fee,long received) {}
    public enum RepayStatus { PAID,CLEARED,NO_DEBT,INSUFFICIENT_CASH }
    public record Repayment(long amount,RepayStatus status,long remaining) {}
    private final Market market;
    private final List<Event> pending=new ArrayList<>();
    private long generation;
    private static final BigInteger MAX=BigInteger.valueOf(Long.MAX_VALUE);
    public Loans(Market market) { this.market=market;generation=market.resetGeneration; }
    private void syncReset() {if(generation!=market.resetGeneration){pending.clear();generation=market.resetGeneration;}}

    public long balance(UUID player) { var account=market.accounts.get(player);return account==null||account.debt==null?0:account.debt.balance(); }
    private GameConfig.LoanRules contractRules(Debt debt) {
        if(debt.contract==null)debt.contract=LoanContract.from(market.config.loanRules());return debt.contract.rules();
    }
    public GameConfig.LoanRules rulesFor(UUID player) {
        var account=market.accounts.get(player);return account==null||account.debt==null?market.config.loanRules():contractRules(account.debt);
    }
    public Map<Kind,OfflineSummary> drainOfflineSummary(UUID player) {
        var account=market.accounts.get(player);if(account==null||account.offlineLoanSummary.isEmpty())return Map.of();
        var summary=Map.copyOf(account.offlineLoanSummary);account.offlineLoanSummary.clear();return summary;
    }
    /** 파산자는 주식을 새로 살 수 없습니다. 가진 주식은 파산 정산 때 이미 처분되며 매도·상환·수입은 허용합니다. */
    public static boolean blocked(Market.Account account,boolean buying) {
        return buying&&account.debt!=null&&account.debt.bankrupt;
    }
    /** 선이자: 빌린 원금에서 미리 떼는 금액입니다. 일찍 갚아도 돌려주지 않습니다. */
    public static long upfrontFee(GameConfig.LoanRules rules,long principal) {return percent(principal,rules.upfrontFeePercent());}

    public Borrowing borrow(UUID player,long principal) {
        syncReset();
        var currentRules=market.config.loanRules();
        GameConfig.require(currentRules.enabled(),"대출 기능이 꺼져 있습니다");
        var account=market.accounts.getOrDefault(player,new Market.Account(market.config.rules().initialCash()));
        // 만기가 지난 기존 대출은 새 차입을 판단하기 전에 먼저 정산합니다.
        if(account.debt!=null)update(player,account);
        var existing=account.debt;
        GameConfig.require(existing==null||!existing.bankrupt,"파산 중에는 빌릴 수 없습니다. 빚을 모두 갚아야 다시 빌릴 수 있습니다");
        var rules=existing==null?currentRules:contractRules(existing);
        GameConfig.require(principal>=rules.minPrincipal()&&principal<=rules.maxPrincipal(),"대출 금액이 설정된 최소·최대 범위를 벗어났습니다");
        if(existing!=null)GameConfig.require(rules.allowTopUp(),"기존 대출을 모두 갚은 뒤 다시 빌릴 수 있습니다");
        long outstanding=existing==null?0:existing.principal;
        GameConfig.require(Math.addExact(outstanding,principal)<=rules.maxPrincipal(),"미상환 원금과 추가 금액의 합계가 대출 한도를 초과합니다");
        long equity=Math.addExact(account.cash,market.value(account));
        if(rules.requireLowAssets())GameConfig.require(equity<=rules.bankruptcyThreshold(),"현금과 주식 평가액이 파산 기준 이하일 때만 빌릴 수 있습니다");
        long fee=upfrontFee(rules,principal),received=principal-fee;
        // 금액·누적 원금·저장 범위가 잘못된 요청은 계정을 바꾸기 전에 모두 거부합니다.
        long cash=Math.addExact(account.cash,received);Math.addExact(cash,market.value(account));
        BigInteger cost=BigInteger.ZERO;for(var position:account.holdings.values())cost=cost.add(BigInteger.valueOf(position.cost));
        GameConfig.require(cost.compareTo(MAX)<=0,"보유 주식 원가가 저장 가능한 금액 한도를 초과했습니다");
        if(existing!=null) {
            long remaining=Math.addExact(existing.principal,principal),original=Math.addExact(existing.originalPrincipal,principal);
            long total=Math.addExact(existing.totalInterest,fee);Math.addExact(remaining,existing.interest);
            // 추가 차입도 선이자를 떼며 원래 만기·다음 이자 시각은 그대로입니다.
            existing.principal=remaining;existing.originalPrincipal=original;existing.totalInterest=total;account.cash=cash;
            return new Borrowing(principal,fee,received);
        }
        var debt=new Debt();debt.contract=LoanContract.from(rules);
        debt.originalPrincipal=debt.principal=principal;debt.totalInterest=fee;
        debt.borrowedAt=market.clockMillis;
        debt.dueAt=Math.addExact(market.clockMillis,rules.termSeconds()*1000L);
        debt.nextInterestAt=Math.addExact(market.clockMillis,rules.interestIntervalSeconds()*1000L);
        account.cash=cash;account.debt=debt;market.accounts.put(player,account);
        warnIfDue(player,debt);
        return new Borrowing(principal,fee,received);
    }
    /** -1은 현재 현금으로 갚을 수 있는 최대 금액입니다. 쌓인 이자를 먼저 갚습니다. */
    public long repay(UUID player,long requested) {
        return repayResult(player,requested).amount();
    }
    public Repayment repayResult(UUID player,long requested) {
        syncReset();
        GameConfig.require(requested==-1||requested>0,"상환 금액은 양수 또는 all이어야 합니다");
        var account=market.accounts.get(player);
        if(account==null||account.debt==null)return new Repayment(0,RepayStatus.NO_DEBT,0);
        update(player,account);
        if(account.debt==null)return new Repayment(0,RepayStatus.CLEARED,0);
        long amount=requested==-1?Math.min(account.cash,account.debt.balance()):Math.min(requested,account.debt.balance());
        if(amount<=0||account.cash<amount)return new Repayment(0,RepayStatus.INSUFFICIENT_CASH,account.debt.balance());
        pay(player,account,amount);
        return new Repayment(amount,account.debt==null?RepayStatus.CLEARED:RepayStatus.PAID,balance(player));
    }
    /** 판매·입금·매도로 현금이 생기면 바로 정리합니다. 파산자는 그 현금이 즉시 빚에 들어갑니다. */
    public void reconcile(UUID player) {
        syncReset();var account=market.accounts.get(player);if(account==null||account.debt==null)return;
        update(player,account);
    }
    private void pay(UUID player,Market.Account account,long amount) {
        var debt=account.debt;
        long interestPaid=Math.min(amount,debt.interest),principalPaid=amount-interestPaid;
        GameConfig.require(principalPaid<=debt.principal,"상환 금액이 남은 대출보다 큽니다");
        account.cash-=amount;debt.interest-=interestPaid;debt.principal-=principalPaid;
        if(debt.balance()==0) {
            account.debt=null;
            pending.add(new Event(player,Kind.REPAID,amount));
        }
    }
    private static long percent(long amount,double percent) {
        return BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(percent)).movePointLeft(2)
                .setScale(0,RoundingMode.HALF_UP).min(new BigDecimal(MAX)).longValueExact();
    }
    /** 선이자를 포함한 총이자가 누적 차입 원금의 상한 비율을 넘지 않게 자릅니다. */
    private void charge(Debt debt,BigInteger requested) {
        long cap=percent(debt.originalPrincipal,contractRules(debt).maxInterestPercent());
        long available=Math.max(0,cap-debt.totalInterest);
        long charged=requested.min(BigInteger.valueOf(available)).longValueExact();
        long interest=Math.addExact(debt.interest,charged),total=Math.addExact(debt.totalInterest,charged);
        Math.addExact(debt.principal,interest);
        debt.interest=interest;debt.totalInterest=total;
    }
    /** 만기 전후·파산 뒤 모두 간격마다 남은 원금의 이율만큼 이자가 쌓입니다. 오래 꺼둔 계정도 한 번에 계산합니다. */
    private void accrue(Debt debt,long through) {
        if(debt.nextInterestAt>through)return;
        var rules=contractRules(debt);long interval=rules.interestIntervalSeconds()*1000L;
        long count=(through-debt.nextInterestAt)/interval+1;
        charge(debt,BigInteger.valueOf(percent(debt.principal,rules.interestPercent())).multiply(BigInteger.valueOf(count)));
        debt.nextInterestAt=nextTime(debt.nextInterestAt,count,interval);
    }
    private static long nextTime(long first,long count,long interval) {
        return BigInteger.valueOf(first).add(BigInteger.valueOf(count).multiply(BigInteger.valueOf(interval))).min(MAX).longValueExact();
    }
    private void update(UUID player,Market.Account account) {
        var debt=account.debt;accrue(debt,market.clockMillis);
        if(debt.bankrupt){seize(player,account);return;}
        if(market.clockMillis>=debt.dueAt){mature(player,account);return;}
        warnIfDue(player,debt);
    }
    private void warnIfDue(UUID player,Debt debt) {
        long warning=market.config.ui().getInt("loans.due-warning-seconds",30)*1000L;
        if(debt.dueWarned||warning<=0||market.clockMillis<debt.dueAt-warning)return;
        debt.dueWarned=true;pending.add(new Event(player,Kind.DUE_SOON,debt.balance()));
    }
    /** 만기: 현금 → 필요한 만큼 주식 강제 처분 순서로 원리금을 회수하고, 그래도 남으면 즉시 파산합니다. */
    private void mature(UUID player,Market.Account account) {
        seize(player,account);if(account.debt==null)return;
        var debt=account.debt;debt.bankrupt=true;debt.bankruptAt=market.clockMillis;debt.collectorRestUntil=0;
        pending.add(new Event(player,Kind.BANKRUPT,debt.balance()));
    }
    /**
     * 현금과 매도 가능한 주식으로 빚을 회수합니다. 다 갚을 수 있으면 필요한 주식만 팔고 나머지는 남깁니다.
     * 모자라면 팔 수 있는 주식은 모두 팔린 상태이며, 0원 상폐 주식은 원가 손실로 압류합니다.
     */
    private void seize(UUID player,Market.Account account) {
        var debt=account.debt;market.validateCollateralSettlement(account,debt.balance(),false);
        long proceeds=market.liquidateCollateral(account,debt.balance());
        if(proceeds>0)pending.add(new Event(player,Kind.COLLATERAL_SOLD,proceeds));
        long paid=Math.min(account.cash,debt.balance());
        if(paid>0){pending.add(new Event(player,Kind.COLLECTED,paid));pay(player,account,paid);}
        if(account.debt==null)return;
        var forfeited=market.forfeitCollateral(account);
        if(forfeited.quantity()>0)pending.add(new Event(player,Kind.COLLATERAL_FORFEITED,forfeited.amount()));
    }
    public List<Event> advance(Set<UUID> online) {
        syncReset();
        if(market.running)for(var entry:market.accounts.entrySet()) {
            var account=entry.getValue();if(account.debt!=null)update(entry.getKey(),account);
        }
        var events=List.copyOf(pending);pending.clear();
        for(var event:events)if(!online.contains(event.player()))rememberOffline(event);
        return events;
    }
    private void rememberOffline(Event event) {
        var account=market.accounts.get(event.player());if(account==null)return;
        var previous=account.offlineLoanSummary.get(event.kind());long count=previous==null?1:nextTime(previous.count(),1,1);
        boolean cumulative=event.kind()==Kind.COLLECTED||event.kind()==Kind.COLLATERAL_SOLD||event.kind()==Kind.COLLATERAL_FORFEITED;
        long amount=previous==null||!cumulative?event.amount():nextTime(previous.amount(),1,event.amount());
        account.offlineLoanSummary.put(event.kind(),new OfflineSummary(count,amount,market.clockMillis));
    }
}
