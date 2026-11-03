package com.google.android.as.oss.http.service;

import android.app.Application;
import android.provider.Settings;

/**
 * Graphite: second lock on the Now Playing database downloads. The first is the network permission of
 * org.graphite.musicdb, which Graphite Now Playing only grants while an update runs. Here a request also has to
 * be for Google's music database storage and inside the update window Graphite Now Playing opened (end time in
 * Settings.Global {@link #SETTING_UPDATE_UNTIL}). Everything else the engine asks for (other Android System
 * Intelligence downloads) is refused.
 */
public final class GraphiteNetworkGate {
  static final String SETTING_UPDATE_UNTIL = "graphite_nowplaying_update_until";

  private static final String[] ALLOWED_PREFIXES = {
    "https://storage.googleapis.com/music-iq-db/",
    // The list of the newest database, the same file Ambient Music Mod checks
    "https://raw.githubusercontent.com/KieronQuinn/AmbientMusicManifest/main/",
  };

  private GraphiteNetworkGate() {}

  /** Public for the downloader's own manifest fetch (org.graphite.musicdb). */
  public static boolean isAllowed(String url) {
    boolean known = false;
    for (String prefix : ALLOWED_PREFIXES) {
      if (url.startsWith(prefix)) {
        known = true;
        break;
      }
    }
    if (!known) {
      return false;
    }
    Application app = currentApplication();
    if (app == null) {
      return false;
    }
    long until = Settings.Global.getLong(app.getContentResolver(), SETTING_UPDATE_UNTIL, 0L);
    return System.currentTimeMillis() < until;
  }

  private static Application currentApplication() {
    try {
      return (Application)
          Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }
}
