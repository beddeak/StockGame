package kr.hetagram.stock;

import java.util.UUID;

/** Bukkit과 분리한 추심 대상/사망 처리 규칙입니다. 추심은 빚을 다 갚을 때까지 끝나지 않습니다. */
final class CollectorPolicy {
    static boolean eligible(Loans.Debt debt,boolean enabled,boolean running,boolean online,boolean npc,boolean dead,boolean attackableMode) {
        return enabled&&running&&online&&!npc&&!dead&&attackableMode&&debt!=null&&debt.bankrupt;
    }
    /** 첫 출동은 저장되는 파산 시장 시각+유예부터, 잡힌 뒤에는 휴식 시각(collectorRestUntil) 이후부터입니다. */
    static boolean eligible(Loans.Debt debt,boolean enabled,boolean running,boolean online,boolean npc,boolean dead,boolean attackableMode,
                            long marketMillis,long graceMillis) {
        return eligible(debt,enabled,running,online,npc,dead,attackableMode)
                &&marketMillis>=0&&graceMillis>=0&&debt.bankruptAt>=0&&marketMillis>=debt.bankruptAt
                &&marketMillis-debt.bankruptAt>=graceMillis&&marketMillis>=debt.collectorRestUntil;
    }
    /** 큰 시장 시각에서도 덧셈 오버플로 없이 다음 출동까지 남은 시간을 계산합니다. */
    static long graceRemainingMillis(Loans.Debt debt,long marketMillis,long graceMillis) {
        if(debt==null||!debt.bankrupt)return 0;
        if(marketMillis<0||debt.bankruptAt<0||marketMillis<debt.bankruptAt)return Math.max(0,graceMillis);
        long grace=Math.max(0,graceMillis-(marketMillis-debt.bankruptAt));
        long rest=debt.collectorRestUntil>marketMillis?debt.collectorRestUntil-marketMillis:0;
        return Math.max(grace,rest);
    }
    static boolean canDamage(UUID target,UUID victim,UUID assignedCollector,UUID damager,boolean eligible) {
        return eligible&&target!=null&&target.equals(victim)&&assignedCollector!=null&&assignedCollector.equals(damager);
    }
    static boolean attackDue(boolean allowAttack,long tick,long nextAttack,double distanceSquared,double range,boolean lineOfSight) {
        return allowAttack&&tick>=nextAttack&&distanceSquared<=range*range&&lineOfSight;
    }
    /**
     * 배정된 추심원의 실제 공격으로 죽은 경우만 "잡힘"으로 봅니다. 채무는 그대로이며 휴식 시간이 지나면 다시 찾아옵니다.
     * 다른 원인의 사망은 아무것도 바꾸지 않습니다.
     */
    static boolean caughtByAssignedCollector(Loans.Debt debt,UUID victim,String storedTarget,UUID assignedCollector,UUID lastDamager,long marketMillis,long restMillis) {
        if(debt==null||!debt.bankrupt||victim==null||!victim.toString().equals(storedTarget)||!canDamage(victim,victim,assignedCollector,lastDamager,true))return false;
        debt.collectorRestUntil=marketMillis>Long.MAX_VALUE-Math.max(0,restMillis)?Long.MAX_VALUE:marketMillis+Math.max(0,restMillis);
        return true;
    }
    private CollectorPolicy() {}
}
