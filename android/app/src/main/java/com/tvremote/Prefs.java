package com.tvremote;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public class Prefs {
    private static final String FILE = "tvremote_prefs";

    private static final String KEY_TV_IP      = "tv_ip";
    private static final String KEY_TV_NAME    = "tv_name";
    private static final String KEY_TV_PAIRED  = "tv_paired";
    private static final String KEY_HAPTIC     = "haptic";
    private static final String KEY_SMOOTH     = "smooth";
    private static final String KEY_KEEP_SCR   = "keep_screen";
    private static final String KEY_THEATER    = "theater";
    private static final String KEY_NAV_MODE   = "nav_mode";
    private static final String KEY_QA_MODE    = "qa_mode";
    private static final String KEY_APPS       = "apps";
    private static final String KEY_VOLUME     = "volume";
    private static final String KEY_CHANNEL    = "channel";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String getTvIp()     { return sp.getString(KEY_TV_IP, ""); }
    public void   setTvIp(String ip)   { sp.edit().putString(KEY_TV_IP, ip).apply(); }
    public String getTvName()   { return sp.getString(KEY_TV_NAME, "التيليفزيون"); }
    public void   setTvName(String n)  { sp.edit().putString(KEY_TV_NAME, n).apply(); }
    public boolean isTvPaired() { return sp.getBoolean(KEY_TV_PAIRED, false); }
    public void    setTvPaired(boolean v) { sp.edit().putBoolean(KEY_TV_PAIRED, v).apply(); }

    public boolean isHaptic()   { return sp.getBoolean(KEY_HAPTIC, true); }
    public void    setHaptic(boolean v)  { sp.edit().putBoolean(KEY_HAPTIC, v).apply(); }
    public boolean isSmooth()   { return sp.getBoolean(KEY_SMOOTH, true); }
    public void    setSmooth(boolean v)  { sp.edit().putBoolean(KEY_SMOOTH, v).apply(); }
    public boolean isKeepScreen(){ return sp.getBoolean(KEY_KEEP_SCR, true); }
    public void    setKeepScreen(boolean v){ sp.edit().putBoolean(KEY_KEEP_SCR, v).apply(); }
    public boolean isTheater()  { return sp.getBoolean(KEY_THEATER, false); }
    public void    setTheater(boolean v) { sp.edit().putBoolean(KEY_THEATER, v).apply(); }

    public String getNavMode()  { return sp.getString(KEY_NAV_MODE, "dpad"); }
    public void   setNavMode(String v)   { sp.edit().putString(KEY_NAV_MODE, v).apply(); }
    public String getQaMode()   { return sp.getString(KEY_QA_MODE, "apps"); }
    public void   setQaMode(String v)    { sp.edit().putString(KEY_QA_MODE, v).apply(); }

    public Set<String> getApps() {
        return sp.getStringSet(KEY_APPS, defaultApps());
    }
    public void setApps(Set<String> apps) { sp.edit().putStringSet(KEY_APPS, apps).apply(); }

    public int  getVolume()  { return sp.getInt(KEY_VOLUME, 50); }
    public void setVolume(int v) { sp.edit().putInt(KEY_VOLUME, v).apply(); }
    public int  getChannel() { return sp.getInt(KEY_CHANNEL, 1); }
    public void setChannel(int v){ sp.edit().putInt(KEY_CHANNEL, v).apply(); }

    private Set<String> defaultApps() {
        Set<String> s = new HashSet<>();
        s.add("Netflix");
        s.add("YouTube");
        s.add("Prime Video");
        s.add("Disney+");
        s.add("Apple TV");
        s.add("HBO Max");
        return s;
    }
}
