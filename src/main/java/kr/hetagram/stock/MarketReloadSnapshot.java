package kr.hetagram.stock;

import java.util.*;

/** reload 실패 시 가격/상폐/뉴스/설정 객체를 원복합니다. 계정 객체와 진행 중 자산은 복제하거나 갈아끼우지 않습니다. */
final class MarketReloadSnapshot {
    private record QuoteState(Market.Quote quote,GameConfig.Stock spec,long price,long previous,boolean delisted,Market.Effect effect,List<Market.Candle> history) {}
    private final GameConfig config;
    private final List<QuoteState> quotes;
    private final long nextNews;
    MarketReloadSnapshot(Market market) {
        config=market.config;nextNews=market.nextNewsMillis;
        quotes=market.quotes.values().stream().map(q->new QuoteState(q,q.spec,q.price,q.previous,q.delisted,q.effect,List.copyOf(q.history))).toList();
    }
    void restore(Market market) {
        market.config=config;market.nextNewsMillis=nextNews;market.quotes.clear();
        for(var state:quotes) {
            var q=state.quote();q.spec=state.spec();q.price=state.price();q.previous=state.previous();q.delisted=state.delisted();q.effect=state.effect();q.history.clear();q.history.addAll(state.history());market.quotes.put(q.spec.id(),q);
        }
    }
}
