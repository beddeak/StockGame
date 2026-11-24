package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ParkourLayoutTest {
    @TempDir Path directory;
    private YamlConfiguration source() throws Exception {
        var yaml=new YamlConfiguration();
        try(var stream=ParkourSettings.class.getResourceAsStream("/parkour.yml")) {
            assertNotNull(stream);yaml.loadFromString(new String(stream.readAllBytes(),StandardCharsets.UTF_8));
        }
        return yaml;
    }
    private ParkourSettings settings() throws Exception {return ParkourSettings.parse(source());}
    @Test void operatingFileIsReadOnlyAndBundledFallbackDoesNotCreateIt() throws Exception {
        Path absent=directory.resolve("absent");var defaults=ParkourSettings.load(absent.toFile());
        assertFalse(Files.exists(absent));assertTrue(defaults.enabled());assertEquals(5_000_000L,defaults.reward());
        assertEquals(120,defaults.cooldownSeconds());assertEquals(10,defaults.minimumRunSeconds());assertEquals(600,defaults.maximumRunSeconds());
        var yaml=source();yaml.set("reward","12345.67");Path file=directory.resolve("parkour.yml");Files.writeString(file,yaml.saveToString());
        byte[] before=Files.readAllBytes(file);var current=ParkourSettings.load(directory.toFile());
        assertEquals(1_234_567L,current.reward());assertArrayEquals(before,Files.readAllBytes(file));
    }
    @Test void defaultIsTwentyFourOrderedLandingsThreeCheckpointsAndClearSpaceAwayFromExistingMap() throws Exception {
        var settings=settings();var plan=ParkourLayout.plan(settings,0,64,0);
        assertEquals(24,plan.landings().size());assertEquals(3,plan.landings().stream().filter(p->p.kind()==ParkourSettings.Kind.CHECKPOINT).count());
        assertEquals(ParkourSettings.Kind.START,plan.start().kind());assertEquals(ParkourSettings.Kind.FINISH,plan.finish().kind());
        assertEquals(72,plan.start().safeSpawn().y());assertEquals(79,plan.finish().safeSpawn().y());assertEquals(68,plan.fallY());
        assertEquals(-62,plan.bounds().minX());assertEquals(-34,plan.bounds().maxX());assertEquals(-2,plan.bounds().minZ());assertEquals(39,plan.bounds().maxZ());
        assertTrue(plan.bounds().maxX()<-32);assertEquals(-29.5,plan.entryPoint().x());assertEquals(66,plan.entryPoint().y());
        for(var point:plan.headroom())assertFalse(plan.edits().containsKey(point),point.toString());
        for(int i=0;i<plan.landings().size();i++)assertEquals(i,plan.landings().get(i).index());
    }
    @Test void fourBlockJumpsHaveFourAirBlocksAndOneUpJumpsStayWithinNormalSprintReach() throws Exception {
        var plan=ParkourLayout.plan(settings(),0,64,0);int fourGaps=0,upJumps=0;
        for(int i=1;i<plan.landings().size();i++) {
            var previous=plan.landings().get(i-1).blocks();var next=plan.landings().get(i).blocks();
            int dx=Math.max(0,Math.max(next.minX()-previous.maxX(),previous.minX()-next.maxX()));
            int dz=Math.max(0,Math.max(next.minZ()-previous.maxZ(),previous.minZ()-next.maxZ()));
            int dy=next.minY()-previous.maxY();double reach=Math.hypot(dx,dz);
            assertTrue(dy>=0&&dy<=1,"Unexpected vertical jump at "+i);
            assertTrue(reach<= (dy==1?4:5),"Normal sprint reach exceeded at "+i+": "+reach);
            if(dy==1)upJumps++;
            if(dy==0&&(dx==5&&dz==0||dz==5&&dx==0)) {
                fourGaps++;
                for(int gap=1;gap<=4;gap++) {
                    int bx=previous.minX()+Integer.signum(next.minX()-previous.minX())*gap;
                    int bz=previous.minZ()+Integer.signum(next.minZ()-previous.minZ())*gap;
                    assertFalse(plan.edits().containsKey(new ParkourLayout.Pos(bx,previous.minY(),bz)));
                }
            }
        }
        assertEquals(3,fourGaps);assertEquals(7,upJumps);
    }
    @Test void bothSingleNeosHaveUnjumpablePillarsBetweenTheLandingsAndOpenOutsideSpace() throws Exception {
        var settings=settings();var plan=ParkourLayout.plan(settings,0,64,0);
        assertEquals(3,settings.pillars().size());
        for(int index:List.of(8,18)) {
            var before=plan.landings().get(index).blocks();var after=plan.landings().get(index+1).blocks();
            int x=(before.minX()+after.minX())/2,z=(before.minZ()+after.minZ())/2,y=before.minY();
            for(int h=0;h<5;h++)assertEquals(settings.palette().pillar(),plan.edits().get(new ParkourLayout.Pos(x,y+h,z)));
            if(before.minX()!=after.minX()) {
                assertFalse(plan.edits().containsKey(new ParkourLayout.Pos(x,y+1,z-1)));
                assertFalse(plan.edits().containsKey(new ParkourLayout.Pos(x,y+1,z+1)));
            }else {
                assertFalse(plan.edits().containsKey(new ParkourLayout.Pos(x-1,y+1,z)));
                assertFalse(plan.edits().containsKey(new ParkourLayout.Pos(x+1,y+1,z)));
            }
        }
    }
    @Test void spiralMakesAFullTurnWithFourOneBlockRisesAroundCentralPillar() throws Exception {
        var settings=settings();var plan=ParkourLayout.plan(settings,0,64,0);var first=plan.landings().get(11).blocks();
        var last=plan.landings().get(15).blocks();assertEquals(first.minX(),last.minX());assertEquals(first.minZ(),last.minZ());assertEquals(first.minY()+4,last.minY());
        for(int i=12;i<=15;i++) {
            var a=plan.landings().get(i-1).blocks();var b=plan.landings().get(i).blocks();
            assertEquals(2,Math.abs(a.minX()-b.minX()));assertEquals(2,Math.abs(a.minZ()-b.minZ()));assertEquals(a.minY()+1,b.minY());
        }
    }
    @Test void landingRecognizesLegitimateEdgeOverhangButRejectsPassingAboveOrBelow() throws Exception {
        var landing=ParkourLayout.plan(settings(),0,64,0).landings().get(2);var blocks=landing.blocks();double y=blocks.maxY()+1;
        assertTrue(landing.containsFeet(blocks.minX()-.28,y,blocks.minZ()+.5));
        assertTrue(landing.containsFeet(blocks.maxX()+1.28,y,blocks.minZ()+.5));
        assertFalse(landing.containsFeet(blocks.minX()-.31,y,blocks.minZ()+.5));
        assertFalse(landing.containsFeet(blocks.minX()+.5,y+1,blocks.minZ()+.5));
        assertFalse(landing.containsFeet(blocks.minX()+.5,y-4,blocks.minZ()+.5));
    }
    @Test void rewardsTimingAndLabelsReloadWithoutChangingGeometry() throws Exception {
        var initial=settings();var changed=source();changed.set("reward",1000);changed.set("cooldown-seconds",15);changed.set("minimum-run-seconds",5);
        changed.set("landings.start.label","new sign");changed.set("text.started","new text");
        assertDoesNotThrow(()->initial.requireSameLayout(ParkourSettings.parse(changed)));
        for(String key:List.of("offset-x","offset-y","sign-offset-x","boundary-margin","clear-above","failure-below-offset-y","landings.warm-up.z")) {
            var moved=source();moved.set(key,moved.getInt(key)+1);assertThrows(IllegalArgumentException.class,()->initial.requireSameLayout(ParkourSettings.parse(moved)),key);
        }
        var disabled=source();disabled.set("enabled",false);assertThrows(IllegalArgumentException.class,()->initial.requireSameLayout(ParkourSettings.parse(disabled)));
    }
    @Test void rejectsCorruptOrderingOverlappingSolidsBlockedHeadroomAndHugeScanVolume() throws Exception {
        var start=source();start.set("landings.start.kind","NORMAL");assertThrows(IllegalArgumentException.class,()->ParkourSettings.parse(start));
        var overlap=source();overlap.set("pillars.single-neo-one.x",11);assertTrue(assertThrows(IllegalArgumentException.class,()->ParkourSettings.parse(overlap)).getMessage().contains("중복"));
        var head=source();head.set("pillars.single-neo-one.x",11);head.set("pillars.single-neo-one.y",2);assertTrue(assertThrows(IllegalArgumentException.class,()->ParkourSettings.parse(head)).getMessage().contains("머리"));
        var huge=source();huge.set("landings.finish.x",128);huge.set("landings.finish.z",128);huge.set("landings.finish.y",64);
        assertTrue(assertThrows(IllegalArgumentException.class,()->ParkourSettings.parse(huge)).getMessage().contains("250,000"));
    }
    @Test void rejectsInvalidRewardsTimesMaterialsAndMissingMessages() throws Exception {
        for(String key:List.of("reward","build-per-tick","maximum-run-seconds","clear-above")) {
            var bad=source();bad.set(key,0);assertThrows(IllegalArgumentException.class,()->ParkourSettings.parse(bad),key);
        }
        var below=source();below.set("failure-below-offset-y",8);assertThrows(IllegalArgumentException.class,()->ParkourSettings.parse(below));
        var times=source();times.set("minimum-run-seconds",30);times.set("maximum-run-seconds",30);assertThrows(IllegalArgumentException.class,()->ParkourSettings.parse(times));
        var missing=source();missing.set("text.finished",null);assertThrows(IllegalArgumentException.class,()->ParkourSettings.parse(missing));
        var material=source();material.set("palette.normal","NOT_A_MATERIAL");assertThrows(IllegalArgumentException.class,()->ParkourSettings.parse(material));
    }
    @Test void outputCollectionsAreImmutableAndTranslationPreservesRelativeGeometry() throws Exception {
        var settings=settings();var initial=ParkourLayout.plan(settings,0,64,0);var moved=ParkourLayout.plan(settings,100,80,-200);
        assertEquals(initial.start().safeSpawn().x()+100,moved.start().safeSpawn().x());
        assertEquals(initial.start().safeSpawn().y()+16,moved.start().safeSpawn().y());
        assertEquals(initial.start().safeSpawn().z()-200,moved.start().safeSpawn().z());
        assertThrows(UnsupportedOperationException.class,()->initial.edits().clear());assertThrows(UnsupportedOperationException.class,()->initial.headroom().clear());
        assertThrows(UnsupportedOperationException.class,()->initial.landings().clear());assertThrows(UnsupportedOperationException.class,()->settings.texts().clear());
    }
}
