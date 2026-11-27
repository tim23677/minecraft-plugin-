package dev.orderhub.command;

import java.util.List;
import org.bukkit.command.CommandSender;

/** Befehlslogik hinter einem Brigadier-Wurzelknoten, unabhaengig von der Bukkit-Befehlsklasse. */
public interface CommandHandler {

  /** Fuehrt den Befehl mit den durch Leerzeichen getrennten Argumenten aus. */
  boolean execute(CommandSender sender, String[] args);

  /** Vorschlaege fuer das letzte (evtl. leere) Argument. */
  List<String> complete(CommandSender sender, String[] args);

  /** Ob der Absender den Befehl ueberhaupt sehen und ausfuehren darf (Wurzelknoten). */
  boolean canUse(CommandSender sender);
}
