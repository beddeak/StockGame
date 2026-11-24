package kr.hetagram.stock;

import org.bukkit.Material;

import java.util.*;

/** 순수 좌표 계획. 실제 월드 조회/건설/충돌 검사/입장과 보상은 런타임 담당입니다. */
public final class ParkourLayout {
    private ParkourLayout() {}
    public record Pos(int x,int y,int z) {}
    public record Point(double x,double y,double z,float yaw,float pitch) {}
    public record Box(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public boolean contains(int x,int y,int z) {return x>=minX&&x<=maxX&&y>=minY&&y<=maxY&&z>=minZ&&z<=maxZ;}
        public boolean contains(double x,double y,double z) {return x>=minX&&x<maxX+1.0&&y>=minY&&y<maxY+1.0&&z>=minZ&&z<maxZ+1.0;}
        public boolean overlaps(Box other) {return minX<=other.maxX&&maxX>=other.minX&&minY<=other.maxY&&maxY>=other.minY&&minZ<=other.maxZ&&maxZ>=other.minZ;}
    }
    public record Landing(int index,String id,ParkourSettings.Kind kind,Box blocks,Point safeSpawn,String label) {
        /** 플레이어 발 위치를 검사합니다. 몸 중심이 최대 0.3칸 걸쳐 서 있는 정상 모서리 착지도 인정합니다. */
        public boolean containsFeet(double x,double y,double z) {
            return Math.abs(y-(blocks.maxY+1.0))<=0.1&&x>blocks.minX-.299&&x<blocks.maxX+1.299&&z>blocks.minZ-.299&&z<blocks.maxZ+1.299;
        }
    }
    public record Plan(List<Landing> landings,Map<Pos,Material> edits,Set<Pos> headroom,Box bounds,
                       Point returnPoint,double fallY,String layoutKey,Point entryPoint) {
        public Landing start() {return landings.getFirst();}
        public Landing finish() {return landings.getLast();}
    }

    /** offset-x/z는 월드 스폰 블록, offset-y와 각 발판 y는 스폰 발밑 바닥 높이 기준입니다. */
    public static Plan plan(ParkourSettings settings,int spawnX,int spawnY,int spawnZ) {
        int originX=Math.addExact(spawnX,settings.offsetX()),originY=Math.addExact(spawnY-1,settings.offsetY()),originZ=Math.addExact(spawnZ,settings.offsetZ());
        var edits=new LinkedHashMap<Pos,Material>();var headroom=new LinkedHashSet<Pos>();var landings=new ArrayList<Landing>();
        for(var spec:settings.landings()) {
            int x=Math.addExact(originX,spec.x()),y=Math.addExact(originY,spec.y()),z=Math.addExact(originZ,spec.z());
            var box=new Box(x,y,z,x+spec.width()-1,y,z+spec.depth()-1);
            for(int bx=box.minX;bx<=box.maxX;bx++)for(int bz=box.minZ;bz<=box.maxZ;bz++) {
                put(edits,new Pos(bx,y,bz),settings.palette().landing(spec.kind()),"발판 "+spec.id());
                for(int dy=1;dy<=settings.clearAbove();dy++)headroom.add(new Pos(bx,y+dy,bz));
            }
            landings.add(new Landing(landings.size(),spec.id(),spec.kind(),box,new Point(x+spec.width()/2.0,y+1,z+spec.depth()/2.0,spec.yaw(),0),spec.label()));
        }
        for(var spec:settings.pillars())for(int dx=0;dx<spec.width();dx++)for(int dz=0;dz<spec.depth();dz++)for(int dy=0;dy<spec.height();dy++)
            put(edits,new Pos(originX+spec.x()+dx,originY+spec.y()+dy,originZ+spec.z()+dz),settings.palette().pillar(),"기둥 "+spec.id());
        for(Pos position:headroom)GameConfig.require(!edits.containsKey(position),"점프맵 발판 머리 공간이 막혔습니다: "+position);
        GameConfig.require(!edits.isEmpty(),"점프맵에 실제 발판이 필요합니다");
        int margin=settings.boundaryMargin();double fallY=spawnY+(double)settings.failureBelowOffsetY();
        int minX=edits.keySet().stream().mapToInt(Pos::x).min().orElseThrow()-margin,maxX=edits.keySet().stream().mapToInt(Pos::x).max().orElseThrow()+margin;
        int minY=Math.min((int)Math.floor(fallY),edits.keySet().stream().mapToInt(Pos::y).min().orElseThrow()),maxY=edits.keySet().stream().mapToInt(Pos::y).max().orElseThrow()+settings.clearAbove();
        int minZ=edits.keySet().stream().mapToInt(Pos::z).min().orElseThrow()-margin,maxZ=edits.keySet().stream().mapToInt(Pos::z).max().orElseThrow()+margin;
        GameConfig.require((long)(maxX-minX+1)*(maxY-minY+1)*(maxZ-minZ+1)<=250000,"점프맵 보호/충돌 영역은 최대 250,000블록입니다");
        GameConfig.require(Math.abs((long)minX)<=29999984&&Math.abs((long)maxX)<=29999984&&Math.abs((long)minZ)<=29999984&&Math.abs((long)maxZ)<=29999984,"점프맵이 월드 좌표 경계를 벗어납니다");
        return new Plan(List.copyOf(landings),Collections.unmodifiableMap(edits),Collections.unmodifiableSet(headroom),
                new Box(minX,minY,minZ,maxX,maxY,maxZ),landings.getFirst().safeSpawn(),fallY,settings.layoutKey(),
                new Point(spawnX+settings.signOffsetX()+.5,spawnY+settings.signOffsetY(),spawnZ+settings.signOffsetZ()+.5,0,0));
    }
    private static void put(Map<Pos,Material> edits,Pos position,Material material,String owner) {
        GameConfig.require(!edits.containsKey(position),"점프맵 구조가 중복됩니다: "+owner+" "+position);edits.put(position,material);
    }
}
