package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 사채업자 한 명의 설치 위치만 저장합니다. 계정·채무·주가·게임 초기화 데이터와 독립된 파일입니다.
 * 파일이 없으면 설치하지 않으며, 단순 load는 기본 파일 생성이나 기존 파일 수정을 하지 않습니다.
 */
public record LenderSettings(boolean enabled,String world,double x,double y,double z,
                             float yaw,float pitch,String skinName) {
    public LenderSettings {
        validate(enabled,world,x,y,z,yaw,pitch,skinName);
    }

    public static LenderSettings disabled() {return new LenderSettings(false,"",0,65,0,0,0,"MHF_Villager");}

    private static void validate(boolean enabled,String world,double x,double y,double z,float yaw,float pitch,String skinName) {
        // Bukkit.getWorld의 이름 조회에만 사용하며 이 이름으로 디렉터리를 만들지 않습니다.
        // 이미 로드된 한글/공백 포함 월드에도 관리자가 현재 위치로 설치할 수 있습니다.
        GameConfig.require(world!=null&&(world.isEmpty()&&!enabled||!world.isBlank()&&world.length()<=128&&!world.chars().anyMatch(Character::isISOControl)),"lender.yml world: 설치 시 128자 이하의 올바른 월드 이름이 필요합니다");
        GameConfig.require(Double.isFinite(x)&&Math.abs(x)<=30000000,"lender.yml x: -30000000~30000000의 유한한 좌표가 필요합니다");
        GameConfig.require(Double.isFinite(z)&&Math.abs(z)<=30000000,"lender.yml z: -30000000~30000000의 유한한 좌표가 필요합니다");
        GameConfig.require(Double.isFinite(y)&&y>=-2048&&y<=2048,"lender.yml y: -2048~2048의 유한한 좌표가 필요합니다");
        GameConfig.require(Float.isFinite(yaw)&&yaw>=-360&&yaw<=360,"lender.yml yaw: -360~360 범위가 필요합니다");
        GameConfig.require(Float.isFinite(pitch)&&pitch>=-90&&pitch<=90,"lender.yml pitch: -90~90 범위가 필요합니다");
        GameConfig.require(skinName!=null&&(skinName.isEmpty()||skinName.matches("[A-Za-z0-9_]{3,16}")),"lender.yml skin-name: 빈 값 또는 Minecraft 닉네임 3~16자를 입력하세요");
    }

    public static LenderSettings load(File directory) throws Exception {
        var file=directory.toPath().resolve("lender.yml");if(!Files.exists(file))return disabled();
        var yaml=new YamlConfiguration();yaml.load(file.toFile());
        GameConfig.require(yaml.isBoolean("enabled"),"lender.yml enabled: true/false가 필요합니다");
        Object world=yaml.get("world"),skin=yaml.get("skin-name");
        GameConfig.require(world instanceof String,"lender.yml world: 문자열이 필요합니다");
        GameConfig.require(skin instanceof String,"lender.yml skin-name: 문자열이 필요합니다");
        double yaw=number(yaml,"yaw"),pitch=number(yaml,"pitch");
        // float 변환 반올림 때문에 허용 범위 밖의 값을 받아들이지 않도록 원래 값도 검사합니다.
        GameConfig.require(yaw>=-360&&yaw<=360&&pitch>=-90&&pitch<=90,"lender.yml yaw/pitch 범위를 확인하세요");
        return new LenderSettings(yaml.getBoolean("enabled"),(String)world,number(yaml,"x"),number(yaml,"y"),number(yaml,"z"),
                (float)yaw,(float)pitch,(String)skin);
    }

    private static double number(YamlConfiguration yaml,String key) {
        Object raw=yaml.get(key);GameConfig.require(raw instanceof Number,"lender.yml "+key+": 숫자가 필요합니다");
        double value=((Number)raw).doubleValue();GameConfig.require(Double.isFinite(value),"lender.yml "+key+": 유한한 숫자가 필요합니다");return value;
    }

    /** NPC 반영/저장 중 어느 쪽이 실패해도 기존 위치로 복구합니다. 호출자는 성공 후에만 메모리를 확정합니다. */
    static void applyAndSave(File directory,LenderSettings previous,LenderSettings next,
                             java.util.function.Consumer<LenderSettings> apply) throws Exception {
        try {apply.accept(next);save(directory,next);}
        catch(Exception failure) {
            try {apply.accept(previous);}catch(Exception rollback){if(rollback!=failure)failure.addSuppressed(rollback);}
            throw failure;
        }
    }

    public static void save(File directory,LenderSettings value) throws Exception {
        GameConfig.require(value!=null,"저장할 사채업자 설정이 없습니다");
        validate(value.enabled,value.world,value.x,value.y,value.z,value.yaw,value.pitch,value.skinName);
        var yaml=new YamlConfiguration();
        yaml.set("enabled",value.enabled);yaml.set("world",value.world);
        yaml.set("x",value.x);yaml.set("y",value.y);yaml.set("z",value.z);
        yaml.set("yaw",value.yaw);yaml.set("pitch",value.pitch);yaml.set("skin-name",value.skinName);
        yaml.setComments("enabled",List.of("/stock lender spawn으로 설치하고 remove로 없앱니다. false이면 자동 소환하지 않습니다."));
        yaml.setComments("world",List.of("설치한 월드 이름. 월드가 아직 로드되지 않으면 소환을 보류하며 위치는 유지합니다."));
        yaml.setComments("x",List.of("발 위치의 정확한 좌표입니다. 주가·플레이어 자산 초기화와 별도로 보존합니다."));
        yaml.setComments("skin-name",List.of("Minecraft 닉네임 3~16자. 빈 값은 기본 플레이어 스킨입니다."));
        Files.createDirectories(directory.toPath());var file=directory.toPath().resolve("lender.yml");
        var temporary=file.resolveSibling("lender.yml.tmp");
        try {
            Files.writeString(temporary,yaml.saveToString(),StandardCharsets.UTF_8);
            if(Files.exists(file))Files.copy(file,file.resolveSibling("lender.yml.bak"),StandardCopyOption.REPLACE_EXISTING);
            try {Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException unsupported){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temporary);}
    }
}
