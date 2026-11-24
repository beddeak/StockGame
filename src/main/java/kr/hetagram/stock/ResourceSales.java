package kr.hetagram.stock;

import org.bukkit.Material;
import java.util.Map;

/** 판매 견적의 정수 계산. 인벤토리를 바꾸기 전에 수량·현금·평가액 범위를 모두 검사합니다. */
final class ResourceSales {
    record Quote(long items,long amount) {}
    static Quote quote(Map<Material,Integer> amounts,Map<Material,Long> prices) {
        long count=0,total=0;
        for(var entry:amounts.entrySet()) {
            GameConfig.require(entry.getValue()>=0,"판매 수량이 잘못되었습니다");
            Long price=prices.get(entry.getKey());if(price==null)continue;
            GameConfig.require(price>0,"판매 단가는 양수여야 합니다");
            count=Math.addExact(count,entry.getValue());total=Math.addExact(total,Math.multiplyExact(price,entry.getValue().longValue()));
        }
        return new Quote(count,total);
    }
    static long cashAfter(Market market,Market.Account account,long income) {
        GameConfig.require(income>0,"판매할 아이템이 없습니다");
        long next=Math.addExact(account.cash,income);Math.addExact(next,market.value(account));return next;
    }
}
