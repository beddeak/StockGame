package kr.hetagram.stock;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 점프맵의 보상·제한·실제 발판 좌표까지 parkour.yml에서 읽습니다. load는 파일을 쓰지 않습니다. */
public record ParkourSettings(boolean enabled,int buildPerTick,int offsetX,int offsetY,int offsetZ,
                              int signOffsetX,int signOffsetY,int signOffsetZ,int boundaryMargin,int clearAbove,long reward,int cooldownSeconds,
                              int minimumRunSeconds,int maximumRunSeconds,int failureBelowOffsetY,
                              Palette palette,List<LandingSpec> landings,List<PillarSpec> pillars,
                              Map<String,String> texts,YamlConfiguration yaml) {
    public enum Kind { START,NORMAL,CHECKPOINT,FINISH }
    public record Palette(Material normal,Material start,Material checkpoint,Material finish,Material pillar) {
        Material landing(Kind kind) {return switch(kind){case START->start;case CHECKPOINT->checkpoint;case FINISH->finish;default->normal;};}
    }
    /** x/z는 발판의 가장 작은 블록 좌표, y는 첫 발판 높이에 더할 값입니다. */
    public record LandingSpec(String id,Kind kind,int x,int y,int z,int width,int depth,String label,float yaw) {}
    /** 기둥은 아래 블록 y부터 height개를 쌓습니다. 발판과 겹치거나 머리 공간을 막으면 거부합니다. */
    public record PillarSpec(String id,int x,int y,int z,int height,int width,int depth) {}

    public static ParkourSettings load(File directory) throws Exception {
        var yaml=new YamlConfiguration();yaml.options().parseComments(true);
        File file=new File(directory,"parkour.yml");
        if(file.isFile())yaml.load(file);
        else try(var stream=ParkourSettings.class.getResourceAsStream("/parkour.yml")) {
            GameConfig.require(stream!=null,"기본 parkour.yml을 찾을 수 없습니다");
            yaml.load(new InputStreamReader(stream,StandardCharsets.UTF_8));
        }
        return parse(yaml);
    }
    public static ParkourSettings parse(YamlConfiguration c) {
        GameConfig.integer(c,"schema",1,1);GameConfig.require(c.isBoolean("enabled"),"enabled: true/false가 필요합니다");
        int offsetY=GameConfig.integer(c,"offset-y",4,64),below=GameConfig.integer(c,"failure-below-offset-y",-64,256);
        int minimum=GameConfig.integer(c,"minimum-run-seconds",0,3600),maximum=GameConfig.integer(c,"maximum-run-seconds",30,7200);
        GameConfig.require(maximum>minimum,"maximum-run-seconds: 최소 완주 시간보다 길어야 합니다");
        GameConfig.number(c,"display.label-height",.5,8);GameConfig.number(c,"display.text-scale",.1,4);
        GameConfig.integer(c,"display.line-width",80,1000);GameConfig.number(c,"display.view-range",.1,8);
        var palette=new Palette(GameConfig.material(c,"palette.normal",true),GameConfig.material(c,"palette.start",true),
                GameConfig.material(c,"palette.checkpoint",true),GameConfig.material(c,"palette.finish",true),GameConfig.material(c,"palette.pillar",true));
        var landings=new ArrayList<LandingSpec>();var section=c.getConfigurationSection("landings");
        GameConfig.require(section!=null&&section.getKeys(false).size()>=2&&section.getKeys(false).size()<=128,"landings: 시작/종료를 포함한 발판 2~128개가 필요합니다");
        for(String id:section.getKeys(false)) {
            GameConfig.require(id.matches("[a-z0-9][a-z0-9-]{0,47}"),"landings: ID는 소문자/숫자/하이픈만 사용하세요: "+id);
            var row=section.getConfigurationSection(id);GameConfig.require(row!=null,"landings."+id+": 발판 설정이 필요합니다");
            Kind kind;try {kind=Kind.valueOf(GameConfig.text(row,"kind").toUpperCase(Locale.ROOT));}
            catch(IllegalArgumentException invalid) {throw new IllegalArgumentException("landings."+id+".kind: START/NORMAL/CHECKPOINT/FINISH 중 하나가 필요합니다",invalid);}
            landings.add(new LandingSpec(id,kind,GameConfig.integer(row,"x",-128,128),GameConfig.integer(row,"y",0,64),
                    GameConfig.integer(row,"z",-128,128),GameConfig.integer(row,"width",1,5),GameConfig.integer(row,"depth",1,5),
                    GameConfig.text(row,"label"),(float)GameConfig.number(row,"yaw",-180,180)));
        }
        GameConfig.require(landings.getFirst().kind()==Kind.START&&landings.getLast().kind()==Kind.FINISH,"landings: 첫 발판은 START, 마지막 발판은 FINISH여야 합니다");
        GameConfig.require(landings.stream().filter(p->p.kind()==Kind.START).count()==1&&landings.stream().filter(p->p.kind()==Kind.FINISH).count()==1,"landings: START/FINISH는 각각 하나만 가능합니다");
        GameConfig.require(below<offsetY+landings.stream().mapToInt(LandingSpec::y).min().orElseThrow(),"failure-below-offset-y: 가장 낮은 발판의 발 위치보다 낮아야 합니다");
        var pillars=new ArrayList<PillarSpec>();var pillarRows=c.getConfigurationSection("pillars");
        GameConfig.require(pillarRows!=null,"pillars: 기둥 목록이 필요합니다(없으면 {})");
        GameConfig.require(pillarRows.getKeys(false).size()<=64,"pillars: 기둥은 최대 64개입니다");
        for(String id:pillarRows.getKeys(false)) {
            var row=pillarRows.getConfigurationSection(id);GameConfig.require(row!=null,"pillars."+id+": 기둥 설정이 필요합니다");
            pillars.add(new PillarSpec(id,GameConfig.integer(row,"x",-128,128),GameConfig.integer(row,"y",0,64),GameConfig.integer(row,"z",-128,128),
                    GameConfig.integer(row,"height",2,16),GameConfig.integer(row,"width",1,5),GameConfig.integer(row,"depth",1,5)));
        }
        var texts=new LinkedHashMap<String,String>();var textRows=c.getConfigurationSection("text");
        GameConfig.require(textRows!=null,"text: 안내 문구가 필요합니다");
        for(String id:textRows.getKeys(false))texts.put(id,GameConfig.text(textRows,id));
        for(String id:List.of("instructions","started","checkpoint","fell","finished","practice-finished","cooldown","cancelled","invalid-mode","too-fast","timeout","not-ready","left","reloaded"))
            GameConfig.require(texts.containsKey(id),"text."+id+": 안내 문구가 필요합니다");
        var result=new ParkourSettings(c.getBoolean("enabled"),GameConfig.integer(c,"build-per-tick",1,5000),
                GameConfig.integer(c,"offset-x",-256,256),offsetY,GameConfig.integer(c,"offset-z",-256,256),
                GameConfig.integer(c,"sign-offset-x",-256,256),GameConfig.integer(c,"sign-offset-y",0,64),GameConfig.integer(c,"sign-offset-z",-256,256),
                GameConfig.integer(c,"boundary-margin",1,4),GameConfig.integer(c,"clear-above",2,6),
                GameConfig.money(c,"reward",1,10000000000L),GameConfig.integer(c,"cooldown-seconds",0,86400),minimum,maximum,below,
                palette,List.copyOf(landings),List.copyOf(pillars),Collections.unmodifiableMap(texts),c);
        // 좌표 모순은 서버에 블록 하나도 놓기 전에 발견합니다. 이 검증은 Bukkit 월드 없이 수행됩니다.
        ParkourLayout.plan(result,0,64,0);
        return result;
    }
    public String text(String key) {String value=texts.get(key);GameConfig.require(value!=null,"parkour.yml text."+key+": 문구가 필요합니다");return value;}
    /** 보상/시간/문구만 reload 가능. 이미 지은 발판을 자동으로 이동·덮어쓰지 않습니다. */
    public String layoutKey() {
        var key=new StringBuilder(enabled+":"+offsetX+":"+offsetY+":"+offsetZ+":"+signOffsetX+":"+signOffsetY+":"+signOffsetZ+":"+boundaryMargin+":"+clearAbove+":"+failureBelowOffsetY+":"+palette);
        for(var p:landings)key.append('|').append(p.id()).append(':').append(p.kind()).append(':').append(p.x()).append(':').append(p.y()).append(':').append(p.z()).append(':').append(p.width()).append(':').append(p.depth()).append(':').append(p.yaw());
        for(var p:pillars)key.append('|').append(p);
        return key.toString();
    }
    public void requireSameLayout(ParkourSettings next) {GameConfig.require(layoutKey().equals(next.layoutKey()),"점프맵 위치·발판·기둥·재료·활성 설정은 서버 재시작 후 반영하세요");}
}
