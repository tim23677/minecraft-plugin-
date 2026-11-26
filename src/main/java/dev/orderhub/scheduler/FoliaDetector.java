package dev.orderhub.scheduler;

/** Erkennt, ob der Server regionalisiert laeuft (Folia). */
final class FoliaDetector {

  private static final boolean FOLIA = detect();

  private FoliaDetector() {}

  static boolean isFolia() {
    return FOLIA;
  }

  private static boolean detect() {
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
