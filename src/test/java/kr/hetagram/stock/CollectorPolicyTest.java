package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CollectorPolicyTest {
    @TempDir Path directory;
    private Loans.Debt bankruptDebt() {var debt=new Loans.Debt();debt.bankrupt=true;return debt;}

    @Test void onlyActiveBankruptPlayersInAttackableModesAreEligible() {
        var debt=bankruptDebt();
        assertTrue(CollectorPolicy.eligible(debt,true,true,true,false,false,true));
        assertFalse(CollectorPolicy.eligible(null,true,true,true,false,false,true));
        debt.bankrupt=false;assertFalse(CollectorPolicy.eligible(debt,true,true,true,false,false,true));
        debt.bankrupt=true;assertTrue(CollectorPolicy.eligible(debt,true,true,true,false,false,true));
    }

    @Test void pauseOfflineNpcDeathAndCreativeSpectatorAllStopPursuitWithoutResolvingTheDebt() {
        var debt=bankruptDebt();
        assertFalse(CollectorPolicy.eligible(debt,false,true,true,false,false,true));
        assertFalse(CollectorPolicy.eligible(debt,true,false,true,false,false,true));
        assertFalse(CollectorPolicy.eligible(debt,true,true,false,false,false,true));
        assertFalse(CollectorPolicy.eligible(debt,true,true,true,true,false,true));
        assertFalse(CollectorPolicy.eligible(debt,true,true,true,false,true,true));
        assertFalse(CollectorPolicy.eligible(debt,true,true,true,false,false,false));
        assertEquals(0,debt.collectorRestUntil);
        assertTrue(CollectorPolicy.eligible(debt,true,true,true,false,false,true));
    }

    @Test void damagePermissionNeverIncludesAnotherPlayerOrInactiveTarget() {
        UUID target=UUID.randomUUID(),bystander=UUID.randomUUID(),assigned=UUID.randomUUID(),otherCollector=UUID.randomUUID();
        assertTrue(CollectorPolicy.canDamage(target,target,assigned,assigned,true));
        assertFalse(CollectorPolicy.canDamage(target,bystander,assigned,assigned,true));
        assertFalse(CollectorPolicy.canDamage(target,target,assigned,assigned,false));
        assertFalse(CollectorPolicy.canDamage(null,target,assigned,assigned,true));
        assertFalse(CollectorPolicy.canDamage(target,target,assigned,otherCollector,true));
        assertFalse(CollectorPolicy.canDamage(target,target,assigned,target,true));
        assertFalse(CollectorPolicy.canDamage(target,target,null,assigned,true));
    }

    @Test void onlyPeriodicUpdatesCanAttackAndTheAttackIntervalIsRespected() {
        long nextAttack=20;int attacks=0;
        for(long tick=1;tick<=80;tick++) {
            assertFalse(CollectorPolicy.attackDue(false,tick,nextAttack,1,1.8,true));
            if(tick%5==0&&CollectorPolicy.attackDue(true,tick,nextAttack,1,1.8,true)) {
                attacks++;nextAttack=tick+20;
            }
        }
        assertEquals(4,attacks);assertEquals(100,nextAttack);
    }

    @Test void collectorsCannotHitThroughWallsOrOutsideTheConfiguredReach() {
        assertTrue(CollectorPolicy.attackDue(true,20,20,1.8*1.8,1.8,true));
        assertFalse(CollectorPolicy.attackDue(true,20,20,1.8*1.8+.001,1.8,true));
        assertFalse(CollectorPolicy.attackDue(true,20,20,1,1.8,false));
        assertFalse(CollectorPolicy.attackDue(true,19,20,1,1.8,true));
    }

    @Test void beingCaughtOnlyRestsTheCollectorAndLeavesTheMoneyOwedIntact() {
        UUID player=UUID.randomUUID(),collector=UUID.randomUUID();
        var debt=bankruptDebt();debt.originalPrincipal=debt.principal=Market.cents("300000");debt.interest=Market.cents("5000");
        long balance=debt.balance();assertTrue(CollectorPolicy.caughtByAssignedCollector(debt,player,player.toString(),collector,collector,1000,60000));
        assertEquals(61000,debt.collectorRestUntil);assertEquals(balance,debt.balance());assertTrue(debt.bankrupt);
        assertFalse(CollectorPolicy.caughtByAssignedCollector(null,player,player.toString(),collector,collector,1000,60000));
        var ordinary=new Loans.Debt();assertFalse(CollectorPolicy.caughtByAssignedCollector(ordinary,player,player.toString(),collector,collector,1000,60000));
        assertEquals(0,ordinary.collectorRestUntil);
    }

    @Test void collectorRestPersistsAndANewLoanDoesNotInheritIt() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
        var config=GameConfig.load(directory.toFile());config.ui().set("loans.enabled",true);
        var market=new Market(config);market.running=true;UUID player=UUID.randomUUID();market.account(player).cash=0;
        var loans=new Loans(market);long principal=config.loanRules().defaultPrincipal();loans.borrow(player,principal);
        market.clockMillis=300000;var debt=market.account(player).debt;debt.bankrupt=true;debt.bankruptAt=300000;debt.collectorRestUntil=360000;
        var store=new StateStore(directory.toFile());store.save(market);byte[] saved=Files.readAllBytes(directory.resolve("state.yml"));
        var restored=new Market(config);store.load(restored);
        assertEquals(360000,restored.account(player).debt.collectorRestUntil);assertEquals(principal,restored.account(player).debt.balance());
        assertArrayEquals(saved,Files.readAllBytes(directory.resolve("state.yml")));
        // 다음 회수 때 이자가 더 붙으므로 넉넉한 현금으로 완납합니다.
        var resumed=new Loans(restored);restored.account(player).cash=Market.cents("10000000");resumed.repay(player,-1);
        assertNull(restored.account(player).debt);
        restored.account(player).cash=0;resumed.borrow(player,principal);assertEquals(0,restored.account(player).debt.collectorRestUntil);
        assertFalse(restored.account(player).debt.bankrupt);
    }

    @Test void invalidSavedCollectorRestIsRejectedWithoutOverwritingTheDamagedState() throws Exception {
        for(String name:List.of("config.yml","stocks.yml","news.yml","map.yml"))
            try(var in=getClass().getResourceAsStream("/"+name)){Files.copy(Objects.requireNonNull(in),directory.resolve(name));}
        var config=GameConfig.load(directory.toFile());config.ui().set("loans.enabled",true);
        var market=new Market(config);market.running=true;UUID player=UUID.randomUUID();market.account(player).cash=0;
        new Loans(market).borrow(player,config.loanRules().defaultPrincipal());
        var store=new StateStore(directory.toFile());store.save(market);
        var yaml=YamlConfiguration.loadConfiguration(directory.resolve("state.yml").toFile());
        yaml.set("accounts."+player+".loan.collector-rest-until","broken");yaml.save(directory.resolve("state.yml").toFile());
        byte[] damaged=Files.readAllBytes(directory.resolve("state.yml"));

        assertThrows(IllegalArgumentException.class,()->store.load(new Market(config)));

        assertArrayEquals(damaged,Files.readAllBytes(directory.resolve("state.yml")));
        assertEquals(0,market.account(player).debt.collectorRestUntil);
        assertEquals(config.loanRules().defaultPrincipal(),market.account(player).debt.balance());
    }
}
