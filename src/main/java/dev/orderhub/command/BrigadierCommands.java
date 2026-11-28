package dev.orderhub.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;

/**
 * Registriert die OrderHub-Befehle ueber Paper-Brigadier (Lifecycle-Event {@code COMMANDS}).
 *
 * <p>Die Wurzelknoten {@code order} (Aliase {@code orderhub}, {@code orders}) und {@code
 * orderadmin} nehmen die restlichen Eingaben als ein gieriges Textargument und reichen sie an einen
 * {@link CommandHandler} weiter. Die Unterbefehle bleiben in den Handlern, weil Berechtigungen,
 * Bestaetigungen und Vorschlaege dort je Unterbefehl geprueft werden. Der Handler ist austauschbar:
 * Bis die Datenbank bereit ist, antwortet ein Platzhalter, danach wird der echte Handler gesetzt.
 */
public final class BrigadierCommands {

  private static final String ARGS = "args";

  private volatile CommandHandler order;
  private volatile CommandHandler admin;

  public BrigadierCommands(CommandHandler order, CommandHandler admin) {
    this.order = Objects.requireNonNull(order, "order");
    this.admin = Objects.requireNonNull(admin, "admin");
  }

  public void setOrder(CommandHandler handler) {
    this.order = Objects.requireNonNull(handler, "handler");
  }

  public void setAdmin(CommandHandler handler) {
    this.admin = Objects.requireNonNull(handler, "handler");
  }

  /** Haengt beide Wurzelknoten in den Paper-Registrar. */
  public void register(Commands registrar) {
    registrar.register(orderNode(), "Open the OrderHub marketplace", List.of("orderhub", "orders"));
    registrar.register(adminNode(), "OrderHub administration", List.of());
  }

  /**
   * Wurzelknoten {@code order}; oeffentlich, damit Tests ihn in einen eigenen Dispatcher haengen.
   */
  public LiteralCommandNode<CommandSourceStack> orderNode() {
    return root("order", () -> order).build();
  }

  /** Wurzelknoten {@code orderadmin}. */
  public LiteralCommandNode<CommandSourceStack> adminNode() {
    return root("orderadmin", () -> admin).build();
  }

  private LiteralArgumentBuilder<CommandSourceStack> root(
      String name, java.util.function.Supplier<CommandHandler> handler) {
    return Commands.literal(name)
        .requires(source -> handler.get().canUse(source.getSender()))
        .executes(ctx -> run(handler.get(), ctx.getSource().getSender(), ""))
        .then(
            RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                    ARGS, StringArgumentType.greedyString())
                .suggests((ctx, builder) -> suggest(handler.get(), ctx, builder))
                .executes(ctx ->
                    run(
                        handler.get(),
                        ctx.getSource().getSender(),
                        StringArgumentType.getString(ctx, ARGS))));
  }

  private static int run(CommandHandler handler, CommandSender sender, String raw) {
    handler.execute(sender, split(raw));
    return Command.SINGLE_SUCCESS;
  }

  private static CompletableFuture<Suggestions> suggest(
      CommandHandler handler, CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    String remaining = builder.getRemaining();
    String[] args = splitForCompletion(remaining);
    int lastSpace = remaining.lastIndexOf(' ');
    SuggestionsBuilder at = builder.createOffset(builder.getStart() + lastSpace + 1);
    for (String candidate : handler.complete(ctx.getSource().getSender(), args)) {
      at.suggest(candidate);
    }
    return at.buildFuture();
  }

  /** Teilt eine ausgefuehrte Eingabe an Leerzeichen; keine Eingabe heisst keine Argumente. */
  static String[] split(String raw) {
    String trimmed = raw.strip();
    return trimmed.isEmpty() ? new String[0] : trimmed.split(" +");
  }

  /**
   * Teilt eine halbe Eingabe wie Bukkit fuer die Vervollstaendigung: Das letzte Argument bleibt
   * auch leer erhalten ({@code "create "} ergibt {@code ["create", ""]}, eine leere Eingabe {@code
   * [""]}), damit die Vorschlaege die naechste Stelle meinen.
   */
  static String[] splitForCompletion(String remaining) {
    return remaining.stripLeading().split(" +", -1);
  }
}
