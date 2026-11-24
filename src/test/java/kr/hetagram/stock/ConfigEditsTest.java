package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ConfigEditsTest {
    @TempDir Path directory;
    private Path file;
    @BeforeEach void setup() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var input=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(input),directory.resolve(name));}
        file=directory.resolve("config.yml");
    }
    private void validate(YamlConfiguration candidate) throws Exception {GameConfig.load(directory.toFile(),candidate);}
    private YamlConfiguration read() throws Exception {
        var yaml=new YamlConfiguration();yaml.options().parseComments(true);yaml.load(file.toFile());return yaml;
    }

    @Test void patchesTheLatestDiskValueOnlyAndPreservesManualValuesAndComments() throws Exception {
        var stale=GameConfig.load(directory.toFile());var manual=read();
        manual.set("game.initial-cash",1234567);manual.set("news.broadcast-chat",false);manual.set("messages.news-title","manual-title");
        manual.setComments("game.initial-cash",List.of("manual cash comment"));manual.save(file.toFile());
        byte[] before=Files.readAllBytes(file);
        Path backup=ConfigEdits.patch(file,Map.of("loans.interest-percent",7.5),5,this::validate);
        var current=GameConfig.load(directory.toFile());
        assertEquals(7.5,current.loanRules().interestPercent());assertEquals(Market.cents("1234567"),current.rules().initialCash());
        assertNotEquals(stale.rules().initialCash(),current.rules().initialCash());
        assertFalse(current.ui().getBoolean("news.broadcast-chat"));assertEquals("manual-title",current.msg("news-title"));
        assertTrue(Files.readString(file).contains("manual cash comment"));assertArrayEquals(before,Files.readAllBytes(backup));
    }

    @Test void fullValidationRejectsInvalidCandidateAndUnrelatedInvalidManualSettingsWithoutChangingEitherFile() throws Exception {
        byte[] before=Files.readAllBytes(file);
        assertThrows(IllegalArgumentException.class,()->ConfigEdits.patch(file,Map.of("loans.default-principal",99999999),5,this::validate));
        assertArrayEquals(before,Files.readAllBytes(file));
        var manual=read();manual.set("game.tick-seconds",-1);manual.save(file.toFile());byte[] invalid=Files.readAllBytes(file);
        assertThrows(IllegalArgumentException.class,()->ConfigEdits.patch(file,Map.of("loans.interest-percent",7.5),5,this::validate));
        assertArrayEquals(invalid,Files.readAllBytes(file));
    }

    @Test void repeatedChangesHaveUniqueRecoverableBackupsAndRetentionDoesNotTouchOtherFiles() throws Exception {
        Path backups=Files.createDirectory(directory.resolve("config-backups"));Path unrelated=backups.resolve("stocks.yml-keep.yml");
        Files.writeString(unrelated,"operator-owned-backup",StandardCharsets.UTF_8);var created=new ArrayList<Path>();
        for(int rate=6;rate<=9;rate++) {
            byte[] before=Files.readAllBytes(file);Path backup=ConfigEdits.patch(file,Map.of("loans.interest-percent",rate),2,this::validate);
            created.add(backup);assertArrayEquals(before,Files.readAllBytes(backup));
        }
        assertEquals(4,new HashSet<>(created).size());assertEquals(9,GameConfig.load(directory.toFile()).loanRules().interestPercent());
        try(var files=Files.list(backups)){assertEquals(2,files.filter(path->path.getFileName().toString().startsWith("config.yml-")).count());}
        assertEquals("operator-owned-backup",Files.readString(unrelated));
        assertTrue(created.stream().allMatch(path->path.getParent().equals(backups)));
    }

    @Test void aConcurrentDiskEditDuringValidationIsNeverOverwrittenAndTheTemporaryFileIsRemoved() throws Exception {
        byte[] before=Files.readAllBytes(file);String concurrent=new String(before,StandardCharsets.UTF_8)+"\n# concurrent operator edit\n";
        assertThrows(IllegalArgumentException.class,()->ConfigEdits.patch(file,Map.of("loans.interest-percent",7.5),5,candidate->{
            validate(candidate);Files.writeString(file,concurrent,StandardCharsets.UTF_8);
        }));
        assertEquals(concurrent,Files.readString(file));assertEquals(5,read().getDouble("loans.interest-percent"));
        try(var files=Files.list(directory)){assertTrue(files.noneMatch(path->path.getFileName().toString().endsWith(".tmp")));}
    }
}
