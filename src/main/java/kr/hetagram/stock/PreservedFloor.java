package kr.hetagram.stock;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/** 기존 바닥 안은 그대로 두고 새 범위의 빈 칸만 확장하는 순수 계획기입니다. 월드 변경은 호출자가 합니다. */
final class PreservedFloor {
    record Bounds(int minX,int minZ,int maxX,int maxZ,int floor) {
        Bounds {GameConfig.require(minX<=maxX&&minZ<=maxZ,"저장된 바닥 범위가 잘못되었습니다");}
        boolean contains(int x,int z) {return x>=minX&&x<=maxX&&z>=minZ&&z<=maxZ;}
        Bounds union(Bounds other) {
            GameConfig.require(floor==other.floor,"기존 바닥 높이와 다릅니다. 보존 모드에서는 새 월드를 사용하세요");
            return new Bounds(Math.min(minX,other.minX),Math.min(minZ,other.minZ),Math.max(maxX,other.maxX),Math.max(maxZ,other.maxZ),floor);
        }
        String marker() {return "floor-bounds:"+minX+","+minZ+","+maxX+","+maxZ+","+floor;}
    }
    record Fill(int x,int y,int z,Material material) {}
    record Plan(Bounds previous,Bounds retained,List<Fill> fills) {Plan {fills=List.copyOf(fills);}}
    interface Reader {Material at(int x,int y,int z);}
    static boolean air(Material material) {return material==Material.AIR||material==Material.CAVE_AIR||material==Material.VOID_AIR;}
    static Bounds bounds(GameConfig config) {
        var c=config.map();int originX=c.getInt("origin.x"),originZ=c.getInt("origin.z"),floor=c.getInt("origin.y"),count=config.stocks().size();
        if(c.getString("layout.style","wall").equals("kiosks")) {
            int columns=c.getInt("kiosk.columns"),rows=(count+columns-1)/columns,margin=c.getInt("kiosk.margin");
            int half=(int)Math.ceil((columns-1)*c.getDouble("kiosk.spacing")/2)+margin;
            return new Bounds(originX-half,originZ-margin-14,originX+half,originZ+(int)Math.ceil((rows-1)*c.getDouble("kiosk.row-spacing"))+30,floor);
        }
        int columns=c.getInt("layout.panel-columns"),rows=(count+columns-1)/columns,w=c.getInt("chart.width"),gap=c.getInt("layout.panel-gap"),margin=c.getInt("layout.floor-margin");
        int width=columns*w+(columns-1)*gap+margin*2,minX=originX-width/2;
        return new Bounds(minX,originZ-margin,minX+width,originZ+rows*c.getInt("layout.row-gap"),floor);
    }
    static Bounds readBounds(String marker) {
        for(String line:marker.split("\\R"))if(line.startsWith("floor-bounds:")) {
            String[] parts=line.substring("floor-bounds:".length()).split(",");GameConfig.require(parts.length==5,"거래소 바닥 마커가 잘못되었습니다");
            return new Bounds(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]),Integer.parseInt(parts[4]));
        }
        return null;
    }
    static Plan plan(Bounds wanted,Bounds previous,Material floor,Reader reader) {
        // 옛 마커에는 범위가 없습니다. 실제 예상 바닥 재질의 외곽을 한 번 찾아 기존 구멍을 보존합니다.
        if(previous==null) {
            int minX=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(int x=wanted.minX;x<=wanted.maxX;x++)for(int z=wanted.minZ;z<=wanted.maxZ;z++)if(reader.at(x,wanted.floor,z)==floor) {
                minX=Math.min(minX,x);minZ=Math.min(minZ,z);maxX=Math.max(maxX,x);maxZ=Math.max(maxZ,z);
            }
            if(minX!=Integer.MAX_VALUE)previous=new Bounds(minX,minZ,maxX,maxZ,wanted.floor);
        }
        Bounds retained=previous==null?wanted:previous.union(wanted);var fills=new ArrayList<Fill>();
        for(int x=wanted.minX;x<=wanted.maxX;x++)for(int z=wanted.minZ;z<=wanted.maxZ;z++) {
            if(previous!=null&&previous.contains(x,z))continue;
            var materials=List.of(Material.BEDROCK,Material.DIRT,floor);boolean compatible=true;
            for(int level=0;level<3;level++) {
                Material old=reader.at(x,wanted.floor-2+level,z);
                if(!air(old)&&old!=materials.get(level)){compatible=false;break;}
            }
            if(!compatible)continue; // 다른 구조가 있는 열은 새 바닥으로 묻거나 덮지 않습니다.
            for(int level=0;level<3;level++)if(air(reader.at(x,wanted.floor-2+level,z)))fills.add(new Fill(x,wanted.floor-2+level,z,materials.get(level)));
        }
        return new Plan(previous,retained,fills);
    }
    private PreservedFloor() {}
}
