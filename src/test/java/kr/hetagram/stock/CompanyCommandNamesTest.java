package kr.hetagram.stock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Player-facing company names resolve to stable IDs; renaming never moves or deletes a holding. */
class CompanyCommandNamesTest {
    @TempDir Path directory;
    private GameConfig config;

    @BeforeEach void setup() throws Exception {
        for (String name : List.of("config.yml", "stocks.yml", "news.yml", "map.yml"))
            try (var in = getClass().getResourceAsStream("/" + name)) {
                Files.copy(Objects.requireNonNull(in), directory.resolve(name));
            }
        config = GameConfig.load(directory.toFile());
    }

    private GameConfig editStocks(Consumer<YamlConfiguration> edit) throws Exception {
        var yaml = new YamlConfiguration();
        yaml.load(directory.resolve("stocks.yml").toFile());
        edit.accept(yaml);
        yaml.save(directory.resolve("stocks.yml").toFile());
        return GameConfig.load(directory.toFile());
    }

    private void restoreStocks() throws Exception {
        try (var in = getClass().getResourceAsStream("/stocks.yml")) {
            Files.copy(Objects.requireNonNull(in), directory.resolve("stocks.yml"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Test void shippedCompanyNamesAndCommandSuggestionsMatchTheirStableIds() {
        assertEquals("은고리궤도운송", config.stocks().get("blueenergy").name());
        var ids = List.of("redtech", "cloud", "deepoil", "greenbio", "blueenergy");
        var commands = List.of("새벽제분", "유리등", "현무방호", "반딧불연산소", "은고리궤도운송");
        assertEquals(commands, config.stockCommandNames());
        for (int i = 0; i < ids.size(); i++) {
            assertEquals(ids.get(i), config.resolveStock(commands.get(i)));
            assertFalse(config.stockCommandNames().contains(ids.get(i)), "ID should not replace company suggestions");
        }
        assertEquals("redtech", config.resolveStock("새벽들"));
        assertEquals("redtech", config.resolveStock("제분소"));
        assertEquals("deepoil", config.resolveStock("현무암"));
        assertEquals("deepoil", config.resolveStock("방호"));
        assertEquals("greenbio", config.resolveStock("반딧불"));
        assertEquals("blueenergy", config.resolveStock("은고리"));
    }

    @Test void fullCompanyNamesAcceptSpacesAndWhitespaceWithoutSubstringMatching() {
        String fullName = config.stocks().get("deepoil").name();
        assertEquals("deepoil", config.resolveStock(fullName));
        assertEquals("deepoil", config.resolveStock(fullName.replace(" ", "")));
        assertEquals("deepoil", config.resolveStock("  " + fullName.replace(" ", "\t  ") + "  "));
        assertEquals("cloud", config.resolveStock(config.stocks().get("cloud").name()));
        assertThrows(IllegalArgumentException.class, () -> config.resolveStock("방호키우기"));
        assertThrows(IllegalArgumentException.class, () -> config.resolveStock("은고리정"));
        assertThrows(IllegalArgumentException.class, () -> config.resolveStock("없는회사"));
    }

    @Test void legacyIdsAreCaseInsensitiveAndNormalizationHandlesColorsAndFullWidthText() {
        assertEquals("deepoil", config.resolveStock("  DeEpOiL  "));
        assertEquals("deepoil", config.resolveStock("ＤＥＥＰＯＩＬ"));
        assertEquals("blueenergy", config.resolveStock("&6은고리궤도운송"));
        assertEquals("blueenergy", config.resolveStock("§c은고리궤도운송"));
        assertThrows(IllegalArgumentException.class, () -> config.resolveStock(""));
        assertThrows(IllegalArgumentException.class, () -> config.resolveStock(" \t "));
    }

    @Test void editedAliasesResolveAndTheFirstAliasBecomesTheCompanySuggestion() throws Exception {
        var updated = editStocks(yaml -> yaml.set("stocks.redtech.command-aliases", List.of("내 회사", "추가별칭")));
        assertEquals(List.of("내 회사", "추가별칭"), updated.stocks().get("redtech").commandAliases());
        assertEquals("redtech", updated.resolveStock("내회사"));
        assertEquals("redtech", updated.resolveStock("  내   회사 "));
        assertEquals("redtech", updated.resolveStock("추가별칭"));
        assertEquals("내회사", updated.stockCommandNames().getFirst());
        assertEquals(5, updated.stockCommandNames().size());
        assertEquals("redtech", updated.resolveStock(updated.stocks().get("redtech").name()));
        assertEquals("redtech", updated.resolveStock("redtech"));
    }

    @Test void omittedAliasesFallBackToCompanyNamesRatherThanInternalIds() throws Exception {
        var updated = editStocks(yaml -> {
            for (String id : config.stocks().keySet()) yaml.set("stocks." + id + ".command-aliases", null);
        });
        assertEquals(updated.stocks().values().stream().map(stock -> stock.name().replaceAll("\\s+", "")).toList(),
                updated.stockCommandNames());
        for (var stock : updated.stocks().values()) {
            assertTrue(stock.commandAliases().isEmpty());
            assertEquals(stock.id(), updated.resolveStock(stock.name()));
            assertEquals(stock.id(), updated.resolveStock(stock.id()));
            assertFalse(updated.stockCommandNames().contains(stock.id()));
        }
    }

    @Test void companyRenamePreservesSavedCashQuantityCostAndQuoteUnderTheOriginalId() throws Exception {
        var previousConfig = editStocks(yaml -> {
            yaml.set("stocks.blueenergy.name", "(아직 미정 회사 이름)");
            yaml.set("stocks.blueenergy.command-aliases", List.of("이전회사"));
        });
        UUID player = UUID.randomUUID();
        var previous = new Market(previousConfig);
        previous.running = true;
        previous.buy(player, "blueenergy", 3);
        previous.setPrice("blueenergy", Market.cents("135000"));
        var account = previous.account(player);
        long cash = account.cash, cost = account.holdings.get("blueenergy").cost;
        var store = new StateStore(directory.toFile());
        store.save(previous);

        var renamedConfig = editStocks(yaml -> {
            yaml.set("stocks.blueenergy.name", "은고리궤도운송");
            yaml.set("stocks.blueenergy.command-aliases", List.of("은고리궤도운송"));
        });
        var restored = new Market(renamedConfig);
        store.load(restored);

        assertEquals("blueenergy", renamedConfig.resolveStock("은고리궤도운송"));
        assertEquals("은고리궤도운송", restored.quote("blueenergy").spec.name());
        assertEquals(Market.cents("135000"), restored.quote("blueenergy").price);
        assertEquals(cash, restored.account(player).cash);
        assertEquals(3, restored.account(player).holdings.get("blueenergy").quantity);
        assertEquals(cost, restored.account(player).holdings.get("blueenergy").cost);
        assertEquals(List.of("blueenergy"), List.copyOf(restored.account(player).holdings.keySet()));
    }

    @Test void aliasesConflictingWithAnotherAliasDisplayNameOrLegacyIdAreRejected() throws Exception {
        var edits = List.<Consumer<YamlConfiguration>>of(
                yaml -> yaml.set("stocks.redtech.command-aliases", List.of("은고리궤도운송")),
                yaml -> yaml.set("stocks.redtech.command-aliases", List.of("유리등극장")),
                yaml -> yaml.set("stocks.redtech.command-aliases", List.of("  ClOuD  ")),
                yaml -> {
                    yaml.set("stocks.redtech.command-aliases", List.of("겹 친 이름"));
                    yaml.set("stocks.cloud.command-aliases", List.of("겹친이름"));
                },
                yaml -> yaml.set("stocks.redtech.name", "은고리 궤도운송"));
        for (int i = 0; i < edits.size(); i++) {
            restoreStocks();
            var edit = edits.get(i);
            assertThrows(IllegalArgumentException.class, () -> editStocks(edit), "conflict fixture " + i);
        }
    }

    @Test void blankNumericAndOverlongAliasesAreRejectedInsteadOfBeingSilentlyCoerced() throws Exception {
        var invalid = List.of(List.of(""), List.of(" \t "), List.of(123), List.of("회사", 123), List.of("가".repeat(97)));
        for (var aliases : invalid) {
            restoreStocks();
            assertThrows(IllegalArgumentException.class,
                    () -> editStocks(yaml -> yaml.set("stocks.redtech.command-aliases", aliases)), aliases.toString());
        }
        restoreStocks();
        assertThrows(IllegalArgumentException.class,
                () -> editStocks(yaml -> yaml.set("stocks.redtech.command-aliases", 123)));
    }
}
