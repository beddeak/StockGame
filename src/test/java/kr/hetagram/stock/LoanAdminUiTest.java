package kr.hetagram.stock;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class LoanAdminUiTest {
    @Test void oldConfigurationGetsAllSixteenControlsWithoutOverlappingTheCloseButton() {
        var config=new YamlConfiguration();String before=config.saveToString();var layout=LoanAdminUi.layout(config);
        assertEquals(45,layout.size());assertEquals(40,layout.closeSlot());assertEquals(16,layout.entries().size());
        assertEquals(16,layout.entries().stream().map(LoanAdminUi.Entry::slot).distinct().count());
        assertTrue(layout.entries().stream().noneMatch(entry->entry.slot()==layout.closeSlot()));
        assertEquals(List.of("allow-top-up","require-low-assets","enabled"),layout.entries().stream().filter(LoanAdminUi.Entry::toggle).map(LoanAdminUi.Entry::key).toList());
        assertEquals(before,config.saveToString());
    }

    @Test void namesMaterialsSlotsAndLoreCanBeCustomizedWithoutJavaChanges() {
        var config=new YamlConfiguration();config.set("ui.loan-admin.title","관리자 대출 설정");
        config.set("ui.loan-admin.size",36);config.set("ui.loan-admin.close-slot",35);
        config.set("ui.loan-admin.close-name","나가기");config.set("ui.loan-admin.close-icon","OAK_DOOR");
        config.set("ui.loan-admin.fields.max-principal.slot",33);
        config.set("ui.loan-admin.fields.max-principal.name","빌려줄 수 있는 금액");
        config.set("ui.loan-admin.fields.max-principal.icon","DIAMOND");
        config.set("ui.loan-admin.fields.max-principal.lore",List.of("현재 한도 {value}원","원 단위로 입력"));
        config.set("ui.loan-admin.number-lore",List.of("{path}: {value}"));
        var layout=LoanAdminUi.layout(config);var limit=layout.entries().getFirst();
        assertEquals(36,layout.size());assertEquals("관리자 대출 설정",layout.title());assertEquals(Material.OAK_DOOR,layout.closeIcon());
        assertEquals("나가기",layout.closeName());assertEquals(33,limit.slot());assertEquals(Material.DIAMOND,limit.icon());
        assertEquals("빌려줄 수 있는 금액",limit.name());assertEquals(List.of("현재 한도 {value}원","원 단위로 입력"),limit.lore());
        assertEquals(List.of("{path}: {value}"),layout.entries().get(1).lore());
    }

    @Test void duplicateAndOutOfRangeControlsCannotHideAnotherAction() {
        for(String path:List.of("fields.max-principal.slot","fields.enabled.slot","close-slot")) {
            var duplicate=new YamlConfiguration();duplicate.set("ui.loan-admin."+path,11);
            assertThrows(IllegalArgumentException.class,()->LoanAdminUi.layout(duplicate),path);
        }
        for(Object invalid:List.of(-1,45,2.5,"10")) {
            var config=new YamlConfiguration();config.set("ui.loan-admin.fields.max-principal.slot",invalid);
            assertThrows(IllegalArgumentException.class,()->LoanAdminUi.layout(config),invalid.toString());
        }
        for(int size:List.of(0,18,28,63)) {
            var config=new YamlConfiguration();config.set("ui.loan-admin.size",size);
            assertThrows(IllegalArgumentException.class,()->LoanAdminUi.layout(config),Integer.toString(size));
        }
    }

    @Test void badMaterialsAndMalformedInstructionsAreRejected() {
        for(String icon:List.of("AIR","UNKNOWN_ITEM")) {
            var config=new YamlConfiguration();config.set("ui.loan-admin.fields.enabled.icon",icon);
            assertThrows(IllegalArgumentException.class,()->LoanAdminUi.layout(config));
        }
        for(Object invalid:List.of("cancel",List.of(),List.of(""),List.of(" "),List.of(1))) {
            var config=new YamlConfiguration();config.set("ui.loan-admin.cancel-keywords",invalid);
            assertThrows(IllegalArgumentException.class,()->LoanAdminUi.layout(config),invalid.toString());
        }
        var malformedLore=new YamlConfiguration();malformedLore.set("ui.loan-admin.number-lore",List.of("valid",1));
        assertThrows(IllegalArgumentException.class,()->LoanAdminUi.layout(malformedLore));
    }

    @Test void numbersRemainInExactEditableWonSecondsAndPercentUnits() {
        assertEquals("3000000",LoanAdminUi.rawValue(3000000L));
        assertEquals("300",LoanAdminUi.rawValue(300.0));
        assertEquals("0.01",LoanAdminUi.rawValue(new BigDecimal("0.0100")));
        assertEquals("1000000",LoanAdminUi.rawValue(new BigDecimal("1E+6")));
        assertEquals("5.5",LoanAdminUi.rawValue(5.5));assertEquals("true",LoanAdminUi.rawValue(true));
    }

    @Test void aQueuedMessageFromAnOldMenuCannotConsumeTheNewInputSession() {
        var inputs=new LoanAdminUi.InputSessions();UUID player=UUID.randomUUID();
        var old=inputs.begin(player,"max-principal","한도","3000000");
        var current=inputs.begin(player,"interest-percent","금리","5");
        assertFalse(inputs.consume(player,old));assertSame(current,inputs.current(player));
        assertTrue(inputs.consume(player,current));assertFalse(inputs.consume(player,current));assertNull(inputs.current(player));
    }

    @Test void retryingTheSameFieldStillRejectsMessagesQueuedForTheFailedAttempt() {
        var inputs=new LoanAdminUi.InputSessions();UUID player=UUID.randomUUID();
        var failed=inputs.begin(player,"max-principal","한도","3000000");assertTrue(inputs.consume(player,failed));
        var retry=inputs.begin(player,"max-principal","한도","3000000");
        assertNotEquals(failed.token(),retry.token());assertFalse(inputs.consume(player,failed));assertTrue(inputs.consume(player,retry));
    }

    @Test void quitAndPluginShutdownInvalidateInputsWhileKeepingOtherPlayersIndependent() {
        var inputs=new LoanAdminUi.InputSessions();UUID one=UUID.randomUUID(),two=UUID.randomUUID();
        var first=inputs.begin(one,"max-principal","한도","3000000");
        var second=inputs.begin(two,"term-seconds","기한","300");
        inputs.forget(one);assertFalse(inputs.consume(one,first));assertSame(second,inputs.current(two));
        inputs.clear();assertFalse(inputs.consume(two,second));assertNull(inputs.current(one));assertNull(inputs.current(two));
    }

    @Test void concurrentChatCallbacksCanConsumeOneInputOnlyOnce() throws Exception {
        var inputs=new LoanAdminUi.InputSessions();UUID player=UUID.randomUUID();
        var request=inputs.begin(player,"max-principal","한도","3000000");
        try(var executor=Executors.newFixedThreadPool(4)) {
            var calls=java.util.stream.IntStream.range(0,16).mapToObj(i->(Callable<Boolean>)()->inputs.consume(player,request)).toList();
            int successes=0;for(var result:executor.invokeAll(calls))if(result.get())successes++;
            assertEquals(1,successes);assertNull(inputs.current(player));
        }
    }
}
