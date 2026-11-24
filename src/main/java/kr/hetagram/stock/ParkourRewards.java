package kr.hetagram.stock;

/**
 * 완주 보상을 지급하기 전의 순수 검증입니다. 현금·평가액·대기 시각을 전부 계산한 뒤 견적만 반환하며,
 * 세션 완료 확정 전에는 계정의 어느 필드도 바꾸지 않습니다. 모든 금액은 정수 전입니다.
 */
final class ParkourRewards {
    record Receipt(long cashAfter,long readyAt) {}

    static Receipt quote(Market market,Market.Account account,long reward,long nowMillis,int cooldownSeconds) {
        GameConfig.require(reward>0,"점프맵 보상은 양수여야 합니다");
        GameConfig.require(nowMillis>=0,"점프맵 보상 기준 시각이 잘못되었습니다");
        GameConfig.require(cooldownSeconds>=0,"점프맵 보상 대기 시간은 음수가 될 수 없습니다");
        GameConfig.require(account.parkourRewardReadyAt>=0,"점프맵 보상 대기 시각이 잘못되었습니다");
        GameConfig.require(nowMillis>=account.parkourRewardReadyAt,"점프맵 보상 대기 시간이 아직 남았습니다");
        long cashAfter=ResourceSales.cashAfter(market,account,reward);
        long readyAt=Math.addExact(nowMillis,Math.multiplyExact((long)cooldownSeconds,1000L));
        return new Receipt(cashAfter,readyAt);
    }
}
