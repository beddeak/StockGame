package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 관리자 단일 값 수정: 디스크 최신 파일을 다시 읽고 그 키만 병합합니다. 수동 편집은 유지합니다. */
final class ConfigEdits {
    @FunctionalInterface interface Validator {void validate(YamlConfiguration candidate) throws Exception;}
    static Path patch(Path file,Map<String,Object> changes,int retention,Validator validator) throws Exception {
        byte[] before=Files.readAllBytes(file);var yaml=new YamlConfiguration();yaml.options().parseComments(true);
        yaml.loadFromString(new String(before,StandardCharsets.UTF_8));changes.forEach(yaml::set);validator.validate(yaml);
        Path backups=file.getParent().resolve("config-backups");Files.createDirectories(backups);
        Path backup=backups.resolve(file.getFileName()+"-"+System.currentTimeMillis()+"-"+UUID.randomUUID()+".yml");Files.write(backup,before,StandardOpenOption.CREATE_NEW);
        Path temporary=file.resolveSibling(file.getFileName()+"-"+UUID.randomUUID()+".tmp");
        try {
            Files.writeString(temporary,yaml.saveToString(),StandardCharsets.UTF_8);
            GameConfig.require(Arrays.equals(before,Files.readAllBytes(file)),"설정 파일이 편집 중입니다. 저장한 뒤 다시 적용하세요");
            try{Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
        try(var files=Files.list(backups)) {
            var history=files.filter(path->path.getFileName().toString().startsWith(file.getFileName()+"-")).sorted(Comparator.<Path>comparingInt(path->path.equals(backup)?0:1).thenComparing(Comparator.comparing(ConfigEdits::modified).reversed())).toList();
            for(int i=retention;i<history.size();i++)Files.deleteIfExists(history.get(i));
        }catch(java.io.IOException e){java.util.logging.Logger.getLogger("StockGame").warning("설정은 저장했지만 옛 백업 정리는 실패했습니다: "+e.getMessage());}
        return backup;
    }
    private static java.nio.file.attribute.FileTime modified(Path file){try{return Files.getLastModifiedTime(file);}catch(java.io.IOException e){return java.nio.file.attribute.FileTime.fromMillis(0);}}
}
