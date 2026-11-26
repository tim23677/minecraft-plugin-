package dev.orderhub.flow;

import dev.orderhub.bootstrap.Messages;
import dev.orderhub.bootstrap.Settings;
import dev.orderhub.config.Limits;
import dev.orderhub.discord.DiscordService;
import dev.orderhub.gui.MenuManager;
import dev.orderhub.item.ItemCodec;
import dev.orderhub.item.ItemMatcher;
import dev.orderhub.scheduler.PluginScheduler;
import dev.orderhub.service.InsightsService;
import dev.orderhub.service.OrderService;
import dev.orderhub.storage.Storage;
import dev.orderhub.tx.ClaimEngine;
import dev.orderhub.tx.DeliveryEngine;
import dev.orderhub.tx.PayoutWorker;
import dev.orderhub.tx.ReturnEngine;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Gemeinsam genutzte Dienste der Bukkit-nahen Ablaeufe. Reine Referenzhalter ohne Logik. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "Reiner Halter gemeinsam genutzter Dienste, keine Wertobjekte")
public record Services(
    Logger logger,
    Storage storage,
    PluginScheduler scheduler,
    Messages messages,
    Supplier<Settings> settings,
    java.util.function.Supplier<dev.orderhub.config.Rates> rates,
    OrderService orders,
    DeliveryEngine delivery,
    ClaimEngine claims,
    ReturnEngine returns,
    PayoutWorker payouts,
    ItemCodec codec,
    ItemMatcher matcher,
    MenuManager menus,
    InsightsService insights,
    DiscordService discord) {

  /** Aktuelle Limits (Teil des Schnappschusses, der bei reload ausgetauscht wird). */
  public Limits limits() {
    return rates.get().limits();
  }
}
