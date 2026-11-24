package kr.hetagram.stock;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.random.RandomGenerator;

/** 채굴·낚시 구역, 판매 단가와 도구 키트를 읽습니다. load만으로 운영 파일을 만들지 않습니다. */
public record WorkSettings(boolean enabled,int buildPerTick,int pathStartOffsetX,int pathWidth,
                           Quarry quarry,Pond pond,Palette palette,Map<Material,Long> catalog,
                           Map<Material,Integer> kit,YamlConfiguration yaml) {
    public record Ore(Material material,int weight) {}
    public record Quarry(boolean enabled,int offsetX,int offsetZ,int size,int depth,int regenerationTicks,int occupiedRetryTicks,List<Ore> ores) {
        public Material choose(RandomGenerator random) {
            int roll=random.nextInt(ores.stream().mapToInt(Ore::weight).sum());
            for(var ore:ores){roll-=ore.weight();if(roll<0)return ore.material();}
            throw new IllegalStateException("광석 가중치 설정 오류");
        }
    }
    /** bite/lure는 거래소 월드에서 던진 낚시찌의 입질 대기·물고기 접근 시간(틱)입니다. 바닐라는 100~600 / 20~80. */
    public record Pond(boolean enabled,int offsetX,int offsetZ,int size,int depth,int biteMinTicks,int biteMaxTicks,int lureMinTicks,int lureMaxTicks) {}
    public record Palette(Material border,Material wall,Material bottom,Material stairs,Material walkway,Material light) {}

    public static WorkSettings load(File directory) throws Exception {
        var yaml=new YamlConfiguration();yaml.options().parseComments(true);
        File file=new File(directory,"work.yml");
        if(file.isFile())yaml.load(file);
        else try(var stream=WorkSettings.class.getResourceAsStream("/work.yml")) {
            GameConfig.require(stream!=null,"기본 work.yml을 찾을 수 없습니다");
            yaml.load(new InputStreamReader(stream,StandardCharsets.UTF_8));
        }
        File pending=new File(directory,"pending-recording-upgrade.yml");
        if(pending.isFile()) {
            var upgrade=new YamlConfiguration();upgrade.load(pending);GameConfig.integer(upgrade,"schema",1,1);
            var prices=upgrade.getConfigurationSection("work.catalog");GameConfig.require(prices!=null,"work.catalog: 새 단가 목록 필요");
            for(String key:prices.getKeys(false))yaml.set("catalog."+key,java.math.BigDecimal.valueOf(GameConfig.money(prices,key,1,10000000000L),2));
            yaml.set("quarry.regeneration-ticks",GameConfig.integer(upgrade,"work.regeneration-ticks",1,72000));
        }
        if(!yaml.isSet("round-resources-only"))yaml.set("round-resources-only",true);
        // 새 점프맵 버튼만 구 설정에 보충합니다. 사용자가 바꾼 단가/구조는 그대로 둡니다.
        var added=List.of("shop.parkour-slot","shop.parkour-icon","shop.parkour-name");
        if(added.stream().anyMatch(key->!yaml.isSet(key))) {
            var defaults=new YamlConfiguration();
            try(var stream=WorkSettings.class.getResourceAsStream("/work.yml")) {
                defaults.load(new InputStreamReader(Objects.requireNonNull(stream),StandardCharsets.UTF_8));
            }
            for(String key:added)if(!yaml.isSet(key))yaml.set(key,defaults.get(key));
        }
        return parse(yaml);
    }
    public static WorkSettings parse(YamlConfiguration c) {
        GameConfig.integer(c,"schema",1,1);
        bool(c,"round-resources-only");
        boolean enabled=bool(c,"enabled"),mineEnabled=bool(c,"quarry.enabled"),pondEnabled=bool(c,"pond.enabled");
        int size=odd(c,"quarry.size",5,15),depth=GameConfig.integer(c,"quarry.depth",1,4);
        GameConfig.require(depth<=size-2,"quarry.depth: 계단을 놓을 수 있도록 size-2 이하로 설정하세요");
        var ores=new ArrayList<Ore>();var section=c.getConfigurationSection("quarry.ores");
        GameConfig.require(section!=null&&!section.getKeys(false).isEmpty(),"quarry.ores: 광석과 가중치가 필요합니다");
        int total=0;
        for(String key:section.getKeys(false)) {
            Material material=materialName(key,true);int weight=GameConfig.integer(section,key,1,1000000);
            GameConfig.require(key.endsWith("_ORE"),"quarry.ores: 채굴 가능한 광석 재료만 사용하세요: "+key);
            total=Math.addExact(total,weight);ores.add(new Ore(material,weight));
        }
        var quarry=new Quarry(mineEnabled,GameConfig.integer(c,"quarry.offset-x",-256,256),GameConfig.integer(c,"quarry.offset-z",-256,256),size,depth,
                GameConfig.integer(c,"quarry.regeneration-ticks",1,72000),GameConfig.integer(c,"quarry.occupied-retry-ticks",1,1200),List.copyOf(ores));
        // 구 work.yml에는 입질 키가 없으므로 기본 버프값(3~12초 대기, 1~2초 접근)을 씁니다.
        int biteMin=optional(c,"pond.bite-min-ticks",60,1,6000),biteMax=optional(c,"pond.bite-max-ticks",240,biteMin,6000);
        int lureMin=optional(c,"pond.lure-min-ticks",20,1,1200),lureMax=optional(c,"pond.lure-max-ticks",40,lureMin,1200);
        var pond=new Pond(pondEnabled,GameConfig.integer(c,"pond.offset-x",-256,256),GameConfig.integer(c,"pond.offset-z",-256,256),
                odd(c,"pond.size",7,15),GameConfig.integer(c,"pond.depth",3,4),biteMin,biteMax,lureMin,lureMax);
        var palette=new Palette(GameConfig.material(c,"palette.border",true),GameConfig.material(c,"palette.wall",true),
                GameConfig.material(c,"palette.bottom",true),GameConfig.material(c,"palette.stairs",true),
                GameConfig.material(c,"palette.walkway",true),GameConfig.material(c,"palette.light",true));
        var catalog=new LinkedHashMap<Material,Long>();var prices=c.getConfigurationSection("catalog");
        GameConfig.require(prices!=null&&!prices.getKeys(false).isEmpty(),"catalog: 판매 단가가 필요합니다");
        GameConfig.require(prices.getKeys(false).size()<=45,"catalog: 상점은 최대 45개 재료를 지원합니다");
        for(String key:prices.getKeys(false))catalog.put(materialName(key,false),GameConfig.money(prices,key,1,10000000000L));
        var kit=new LinkedHashMap<Material,Integer>();var tools=c.getConfigurationSection("kit");
        GameConfig.require(tools!=null&&!tools.getKeys(false).isEmpty(),"kit: 지급할 도구가 필요합니다");
        for(String key:tools.getKeys(false))kit.put(materialName(key,false),GameConfig.integer(tools,key,1,64));
        GameConfig.require(Collections.disjoint(catalog.keySet(),kit.keySet()),"무료 지급 도구는 catalog 판매 목록에 넣을 수 없습니다");
        bool(c,"kit-unbreakable");
        for(String key:List.of("title","item-name","receipt","kit-message","inventory-full"))GameConfig.text(c,"shop."+key);
        Object lore=c.get("shop.item-lore");GameConfig.require(lore instanceof List<?> && ((List<?>)lore).stream().allMatch(String.class::isInstance),"shop.item-lore: 문자열 목록이 필요합니다");
        var slots=new HashSet<Integer>();
        for(String key:List.of("sell","mine","fish","kit","parkour","close")) {
            int slot=GameConfig.integer(c,"shop."+key+"-slot",45,53);GameConfig.require(slots.add(slot),"shop: 버튼 슬롯이 중복됩니다");
            GameConfig.text(c,"shop."+key+"-name");GameConfig.material(c,"shop."+key+"-icon",false);
        }
        int pathStart=GameConfig.integer(c,"path.start-offset-x",-256,256),pathWidth=odd(c,"path.width",1,5);
        if(mineEnabled)GameConfig.require(pathStart<quarry.offsetX()-quarry.size()/2-1,"path.start-offset-x: 채굴장 왼쪽보다 작아야 합니다");
        if(pondEnabled)GameConfig.require(pathStart<pond.offsetX()-pond.size()/2-1,"path.start-offset-x: 낚시터 왼쪽보다 작아야 합니다");
        for(String key:List.of("text.quarry","text.pond"))GameConfig.text(c,key);
        return new WorkSettings(enabled,GameConfig.integer(c,"build-per-tick",1,5000),pathStart,pathWidth,quarry,pond,palette,
                Collections.unmodifiableMap(catalog),Collections.unmodifiableMap(kit),c);
    }
    private static boolean bool(YamlConfiguration c,String key) {GameConfig.require(c.isBoolean(key),key+": true/false가 필요합니다");return c.getBoolean(key);}
    private static int optional(YamlConfiguration c,String key,int fallback,int min,int max) {
        int value=c.isSet(key)?GameConfig.integer(c,key,min,max):fallback;GameConfig.require(value>=min&&value<=max,key+": 허용 범위 "+min+"~"+max);return value;
    }
    private static int odd(YamlConfiguration c,String key,int min,int max) {int value=GameConfig.integer(c,key,min,max);GameConfig.require(value%2==1,key+": 홀수가 필요합니다");return value;}
    private static Material materialName(String key,boolean block) {
        Material material=Material.matchMaterial(key);
        GameConfig.require(material!=null&&material!=Material.AIR,"잘못된 재료: "+key);
        if(org.bukkit.Bukkit.getServer()!=null)GameConfig.require(block?material.isBlock():material.isItem(),"잘못된 재료 종류: "+key);
        return material;
    }
    /** 가격·키트·문구를 바꿀 때 블록 구조를 재생성할 필요가 있는지 비교하는 값입니다. */
    public String layoutKey() {return enabled+":"+quarry.enabled()+":"+quarry.offsetX()+":"+quarry.offsetZ()+":"+quarry.size()+":"+quarry.depth()+":"+
            pond.enabled()+":"+pond.offsetX()+":"+pond.offsetZ()+":"+pond.size()+":"+pond.depth()+":"+pathStartOffsetX+":"+pathWidth+":"+palette;}
    public void requireSameLayout(WorkSettings next) {GameConfig.require(layoutKey().equals(next.layoutKey()),"채굴장/낚시터 위치·구조·활성 설정은 서버 재시작 후 반영하세요");}
}
