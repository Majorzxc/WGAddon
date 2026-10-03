package me.majorzxc.wgregionlist.command;

import me.majorzxc.wgregionlist.WGRegionListPlugin;
import me.majorzxc.wgregionlist.config.Settings;
import me.majorzxc.wgregionlist.sync.LoadSummary;
import me.majorzxc.wgregionlist.sync.RegionService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Команда /wgrl (/wgregionlist): reload, info, find, save. */
public final class WgrlCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("reload", "info", "find", "save");
    private static final int MAX_SUGGESTIONS = 50;

    private final WGRegionListPlugin plugin;

    public WgrlCommand(WGRegionListPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> reload(sender);
            case "info" -> info(sender);
            case "save" -> save(sender);
            case "find" -> {
                if (args.length < 2) {
                    send(sender, Component.text("Использование: /" + label + " find <id региона>", NamedTextColor.RED));
                } else {
                    find(sender, args[1]);
                }
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        send(sender, Component.text("WGRegionList " + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));
        send(sender, line("/" + label + " reload", "перечитать config.yml и все файлы регионов"));
        send(sender, line("/" + label + " info", "миры, папки, файлы и количество регионов"));
        send(sender, line("/" + label + " find <id>", "в каком файле находится регион"));
        send(sender, line("/" + label + " save", "сразу сохранить изменения, сделанные в игре"));
    }

    private void reload(CommandSender sender) {
        Settings settings;
        try {
            settings = plugin.loadSettings();
        } catch (Exception e) {
            send(sender, Component.text("config.yml не прочитан: " + e.getMessage()
                    + " — прежние настройки сохранены", NamedTextColor.RED));
            return;
        }
        send(sender, Component.text("Перезагрузка регионов...", NamedTextColor.GRAY));
        run(sender, service -> service.reload(settings), result -> {
            List<Component> lines = new ArrayList<>();
            int problems = 0;
            for (LoadSummary summary : result.loaded()) {
                problems += summary.problems() + summary.conflicts();
                lines.add(Component.text(" " + summary.world() + ": ", NamedTextColor.YELLOW)
                        .append(Component.text(summary.regions() + " регионов из " + summary.files() + " файлов",
                                NamedTextColor.WHITE))
                        .append(summary.problems() + summary.conflicts() > 0
                                ? Component.text(" (предупреждений: " + (summary.problems() + summary.conflicts()) + ")",
                                NamedTextColor.RED)
                                : Component.empty()));
            }
            for (String world : result.notLoaded()) {
                lines.add(Component.text(" " + world + ": мир не загружен (регионы подключатся при загрузке мира)",
                        NamedTextColor.GRAY));
            }
            lines.add(0, Component.text("Регионы перезагружены.", NamedTextColor.GREEN));
            if (problems > 0) {
                lines.add(Component.text("Подробности о предупреждениях — в консоли сервера.", NamedTextColor.GRAY));
            }
            return lines;
        });
    }

    private void info(CommandSender sender) {
        run(sender, RegionService::status, status -> {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.text("WGRegionList " + plugin.getPluginMeta().getVersion(), NamedTextColor.GOLD));
            lines.add(line("Сохранение изменений из игры",
                    status.syncEnabled() ? "включено (каждые " + status.syncInterval() + " с)" : "выключено"));
            if (!status.fastIndex()) {
                lines.add(Component.text("Медленный режим загрузки (нет доступа к индексу WorldGuard)", NamedTextColor.RED));
            }
            if (status.worlds().isEmpty()) {
                lines.add(Component.text("В config.yml не указано ни одного мира", NamedTextColor.RED));
            }
            for (RegionService.WorldInfo world : status.worlds()) {
                Component head = Component.text(world.world(), NamedTextColor.YELLOW)
                        .append(Component.text(" → " + String.join(", ", world.folders()), NamedTextColor.GRAY));
                lines.add(head);
                if (!world.loaded()) {
                    lines.add(Component.text("   мир не загружен", NamedTextColor.GRAY));
                    continue;
                }
                String text = "   регионов: " + world.regions() + ", файлов: " + world.files();
                lines.add(Component.text(text, NamedTextColor.WHITE));
                if (world.failedFiles() > 0 || world.conflicts() > 0 || world.problems() > 0) {
                    lines.add(Component.text("   файлов с ошибками: " + world.failedFiles()
                            + ", конфликтов ID: " + world.conflicts()
                            + ", предупреждений: " + world.problems() + " (см. консоль)", NamedTextColor.RED));
                }
            }
            return lines;
        });
    }

    private void find(CommandSender sender, String id) {
        run(sender, service -> service.find(id), results -> {
            List<Component> lines = new ArrayList<>();
            if (results.isEmpty()) {
                lines.add(Component.text("Регион '" + id + "' не найден", NamedTextColor.RED));
            }
            for (RegionService.FindResult result : results) {
                lines.add(Component.text(result.world() + ": ", NamedTextColor.YELLOW)
                        .append(Component.text(result.addon() ? result.location() : "в самом WorldGuard",
                                result.addon() ? NamedTextColor.WHITE : NamedTextColor.GRAY)));
            }
            return lines;
        });
    }

    private void save(CommandSender sender) {
        if (!plugin.settings().syncEnabled()) {
            send(sender, Component.text("Сохранение изменений из игры выключено (sync.enabled: false)", NamedTextColor.RED));
            return;
        }
        run(sender, RegionService::saveNow, written -> List.of(
                Component.text("Готово, записано файлов: " + written, NamedTextColor.GREEN)));
    }

    /** Выполняет задачу в рабочем потоке и показывает результат в основном потоке. */
    private <T> void run(CommandSender sender, Function<RegionService, CompletableFuture<T>> task,
                         Function<T, List<Component>> render) {
        CompletableFuture<T> future;
        try {
            future = task.apply(plugin.service());
        } catch (RuntimeException e) {
            send(sender, Component.text("Плагин выключается — команда не выполнена", NamedTextColor.RED));
            return;
        }
        future.whenComplete((result, error) -> plugin.runOnMainThread(() -> {
            if (error != null) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "Ошибка команды /wgrl", error);
                send(sender, Component.text("Ошибка: " + error.getMessage() + " (подробности в консоли)", NamedTextColor.RED));
                return;
            }
            render.apply(result).forEach(component -> send(sender, component));
        }));
    }

    private static Component line(String key, String description) {
        return Component.text(key, NamedTextColor.YELLOW).append(Component.text(" — " + description, NamedTextColor.GRAY));
    }

    private static void send(CommandSender sender, Component message) {
        sender.sendMessage(Component.text("[WGRL] ", NamedTextColor.DARK_GREEN).append(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("find")) {
            return filter(plugin.knownRegionIds(), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                result.add(option);
                if (result.size() >= MAX_SUGGESTIONS) {
                    break;
                }
            }
        }
        return result;
    }
}
