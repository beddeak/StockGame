package kr.hetagram.stock;

import java.util.*;

/**
 * 가격 이력의 행과 재사용할 표시 칸을 연결하는 순수 자바 계획입니다.
 * Candle은 값이 같아도 새로 생성된 거래 이력일 수 있으므로 equals가 아닌 객체 동일성으로 비교합니다.
 * 화면 밖 두 칸을 남겨 퇴장 중인 행을 곧바로 새 가격으로 바꾸지 않게 합니다.
 */
final class TickerRows<T> {
    record Row<T>(int slot,T value,int fromLevel,int toLevel,boolean entering) {}
    record Exit(int slot,int fromLevel,int toLevel) {}
    record Frame<T>(boolean changed,boolean snap,List<Row<T>> rows,List<Exit> exits) {}
    private record Entry<T>(int slot,T value) {}
    private final int limit;
    private List<Entry<T>> previous=List.of();
    private Set<Integer> retiring=Set.of();
    private Object owner;
    private boolean initialized;

    TickerRows(int limit) {
        if(limit<1)throw new IllegalArgumentException("가격 이력 줄 수는 양수여야 합니다");
        this.limit=limit;
    }
    int poolSize() {return limit+2;}

    Frame<T> update(Object nextOwner,List<T> history) {
        var values=List.copyOf(history.subList(Math.max(0,history.size()-limit),history.size()));
        boolean ownerChanged=!initialized||owner!=nextOwner;
        boolean identical=!ownerChanged&&previous.size()==values.size();
        for(int i=0;identical&&i<values.size();i++)identical=previous.get(i).value()==values.get(i);
        if(identical) {
            var rows=new ArrayList<Row<T>>();
            for(int i=0;i<previous.size();i++) {
                var e=previous.get(i);int level=previous.size()-1-i;
                rows.add(new Row<>(e.slot(),e.value(),level,level,false));
            }
            return new Frame<>(false,false,List.copyOf(rows),List.of());
        }

        var old=new IdentityHashMap<T,Entry<T>>();var oldLevels=new HashMap<Integer,Integer>();
        if(!ownerChanged)for(int i=0;i<previous.size();i++) {
            var e=previous.get(i);old.put(e.value(),e);oldLevels.put(e.slot(),previous.size()-1-i);
        }
        var reserved=new HashSet<Integer>();
        for(T value:values)if(old.containsKey(value))reserved.add(old.get(value).slot());
        // 오래 주차된 빈 칸을 우선하고, 직전 프레임 퇴장 칸은 한 번 더 쉬게 합니다.
        var available=new ArrayList<Integer>();
        for(int slot=0;slot<poolSize();slot++)if(!reserved.contains(slot))available.add(slot);
        available.sort(Comparator.comparingInt((Integer slot)->oldLevels.containsKey(slot)?2:retiring.contains(slot)?1:0).thenComparingInt(Integer::intValue));
        var rows=new ArrayList<Row<T>>();var next=new ArrayList<Entry<T>>();var used=new HashSet<Integer>();int cursor=0;
        for(int i=0;i<values.size();i++) {
            T value=values.get(i);var existing=old.get(value);int slot=existing==null?available.get(cursor++):existing.slot();
            int level=values.size()-1-i;
            rows.add(new Row<>(slot,value,existing==null?-1:oldLevels.get(slot),level,existing==null));
            next.add(new Entry<>(slot,value));used.add(slot);
        }
        var exits=new ArrayList<Exit>();
        if(!ownerChanged)for(var e:previous)if(!used.contains(e.slot()))
            exits.add(new Exit(e.slot(),oldLevels.get(e.slot()),Math.max(limit,oldLevels.get(e.slot())+1)));
        boolean snap=ownerChanged||(!previous.isEmpty()&&reserved.isEmpty());
        previous=List.copyOf(next);owner=nextOwner;initialized=true;
        var nextRetiring=new HashSet<Integer>();for(var exit:exits)nextRetiring.add(exit.slot());retiring=Set.copyOf(nextRetiring);
        return new Frame<>(true,snap,List.copyOf(rows),List.copyOf(exits));
    }
}
