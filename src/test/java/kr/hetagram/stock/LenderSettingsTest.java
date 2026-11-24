package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LenderSettingsTest {
    @TempDir Path directory;

    private LenderSettings placed() {return new LenderSettings(true,"stock_exchange_kiosks",12.25,65.5,-17.75,125.5f,-12.25f,"Trader_42");}

    @Test void missingSettingsDisableManualNpcWithoutCreatingAnyFileOrDirectory() throws Exception {
        var absent=directory.resolve("not-created");
        assertEquals(LenderSettings.disabled(),LenderSettings.load(absent.toFile()));
        assertFalse(Files.exists(absent));
        assertEquals("",LenderSettings.load(directory.toFile()).world());
        assertFalse(Files.exists(directory.resolve("lender.yml")));
    }

    @Test void installedPositionFacingAndSkinRoundtripAndLoadNeverRewrites() throws Exception {
        var placed=placed();LenderSettings.save(directory.toFile(),placed);
        byte[] before=Files.readAllBytes(directory.resolve("lender.yml"));
        assertEquals(placed,LenderSettings.load(directory.toFile()));
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("lender.yml")));
        assertFalse(Files.exists(directory.resolve("lender.yml.tmp")));
        assertFalse(Files.exists(directory.resolve("lender.yml.bak")));
    }

    @Test void secondSaveBacksUpTheExactPreviousSettingsAndLeavesAccountsAndNewsAlone() throws Exception {
        Files.writeString(directory.resolve("state.yml"),"accounts: preserve-this-save\n");
        Files.writeString(directory.resolve("news.yml"),"news: preserve-user-headlines\n");
        var first=placed();LenderSettings.save(directory.toFile(),first);
        byte[] before=Files.readAllBytes(directory.resolve("lender.yml"));
        var moved=new LenderSettings(true,"world_nether",-2.5,74,7.125,-180,35,"Another_Name");

        LenderSettings.save(directory.toFile(),moved);

        assertEquals(moved,LenderSettings.load(directory.toFile()));
        assertArrayEquals(before,Files.readAllBytes(directory.resolve("lender.yml.bak")));
        assertEquals("accounts: preserve-this-save\n",Files.readString(directory.resolve("state.yml")));
        assertEquals("news: preserve-user-headlines\n",Files.readString(directory.resolve("news.yml")));
        assertFalse(Files.exists(directory.resolve("lender.yml.tmp")));
    }

    @Test void disabledConfigurationSupportsTheBlankWorldAndDefaultSkin() throws Exception {
        var disabled=LenderSettings.disabled();LenderSettings.save(directory.toFile(),disabled);
        assertEquals(disabled,LenderSettings.load(directory.toFile()));
        var configuredButHidden=new LenderSettings(false,"world",0,65,0,0,0,"");
        LenderSettings.save(directory.toFile(),configuredButHidden);
        assertEquals(configuredButHidden,LenderSettings.load(directory.toFile()));
    }

    @Test void nicknameAndLocationLimitsAcceptTheirExactBoundaries() throws Exception {
        var edge=new LenderSettings(true,"world",30000000,2048,-30000000,360,-90,"1234567890ABCDEF");
        LenderSettings.save(directory.toFile(),edge);assertEquals(edge,LenderSettings.load(directory.toFile()));
        var otherEdge=new LenderSettings(true,"world",-30000000,-2048,30000000,-360,90,"a_1");
        LenderSettings.save(directory.toFile(),otherEdge);assertEquals(otherEdge,LenderSettings.load(directory.toFile()));
    }

    @Test void invalidConstructedSettingsFailBeforeTheyCanBeSaved() {
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"",0,65,0,0,0,""));
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true," \t ",0,65,0,0,0,""));
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"world\nname",0,65,0,0,0,""));
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"w".repeat(129),0,65,0,0,0,""));
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"world",Double.NaN,65,0,0,0,""));
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"world",0,Double.POSITIVE_INFINITY,0,0,0,""));
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"world",0,65,30000000.01,0,0,""));
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"world",0,-2048.01,0,0,0,""));
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"world",0,65,0,Float.NaN,0,""));
        assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"world",0,65,0,0,90.1f,""));
        for(String invalid:List.of("a","ab","a".repeat(17),"Player-42","한글"))
            assertThrows(IllegalArgumentException.class,()->new LenderSettings(true,"world",0,65,0,0,0,invalid),invalid);
    }

    @Test void invalidYamlTypesRangesAndRoundedFloatOverflowAreRejectedWithoutRewriting() throws Exception {
        LenderSettings.save(directory.toFile(),placed());
        byte[] valid=Files.readAllBytes(directory.resolve("lender.yml"));
        var edits=List.<Consumer<YamlConfiguration>>of(
                yaml->yaml.set("enabled","true"),yaml->yaml.set("world",""),yaml->yaml.set("world",123),
                yaml->yaml.set("x",30000000.01),yaml->yaml.set("z",Double.NaN),yaml->yaml.set("y",2048.01),
                yaml->yaml.set("x","12.5"),yaml->yaml.set("yaw",360.000001),yaml->yaml.set("pitch",90.000001),
                yaml->yaml.set("skin-name",123),yaml->yaml.set("skin-name","ab"),yaml->yaml.set("skin-name",null));
        for(int index=0;index<edits.size();index++) {
            Files.write(directory.resolve("lender.yml"),valid);
            var yaml=new YamlConfiguration();yaml.load(directory.resolve("lender.yml").toFile());
            edits.get(index).accept(yaml);yaml.save(directory.resolve("lender.yml").toFile());
            byte[] invalid=Files.readAllBytes(directory.resolve("lender.yml"));
            assertThrows(IllegalArgumentException.class,()->LenderSettings.load(directory.toFile()),"fixture "+index);
            assertArrayEquals(invalid,Files.readAllBytes(directory.resolve("lender.yml")));
        }
    }

    @Test void backupFailureLeavesTheOldSettingsAndRemovesTheTemporaryWrite() throws Exception {
        LenderSettings.save(directory.toFile(),placed());
        byte[] old=Files.readAllBytes(directory.resolve("lender.yml"));
        Path backup=Files.createDirectory(directory.resolve("lender.yml.bak"));
        Files.writeString(backup.resolve("keep.txt"),"unrelated content");
        assertThrows(java.io.IOException.class,()->LenderSettings.save(directory.toFile(),LenderSettings.disabled()));
        assertArrayEquals(old,Files.readAllBytes(directory.resolve("lender.yml")));
        assertEquals("unrelated content",Files.readString(backup.resolve("keep.txt")));
        assertFalse(Files.exists(directory.resolve("lender.yml.tmp")));
    }

    @Test void invalidSaveInputCannotCreateDirectoriesOrOverwriteTheExistingSettings() throws Exception {
        Path absent=directory.resolve("invalid-output");
        assertThrows(IllegalArgumentException.class,()->LenderSettings.save(absent.toFile(),null));
        assertFalse(Files.exists(absent));
        LenderSettings.save(directory.toFile(),placed());byte[] old=Files.readAllBytes(directory.resolve("lender.yml"));
        assertThrows(IllegalArgumentException.class,()->LenderSettings.save(directory.toFile(),null));
        assertArrayEquals(old,Files.readAllBytes(directory.resolve("lender.yml")));
    }

    @Test void alreadyLoadedWorldNamesMayContainKoreanAndSpacesWithoutCreatingWorldDirectories() throws Exception {
        var settings=new LenderSettings(true,"우리 서버 건축 월드",1.5,65,2.5,0,0,"");
        LenderSettings.save(directory.toFile(),settings);
        assertEquals(settings,LenderSettings.load(directory.toFile()));
        assertFalse(Files.exists(directory.resolve("우리 서버 건축 월드")));
    }
}
