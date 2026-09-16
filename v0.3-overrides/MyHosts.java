package com.remotephone.direct;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class MyHosts {
    private static final String PREF = "controller_hosts";
    private static final String KEY = "hosts_json";

    public static final class HostRecord {
        public String remoteId;
        public String name;
        public String localAddress;

        public HostRecord(String remoteId, String name, String localAddress) {
            this.remoteId = remoteId == null ? "" : remoteId;
            this.name = name == null ? "" : name;
            this.localAddress = localAddress == null ? "" : localAddress;
        }
    }

    private MyHosts() {}

    public static List<HostRecord> load(Context c) {
        ArrayList<HostRecord> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, "[]");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String id = o.optString("remoteId", "");
                if (id.length() != 9) continue;
                out.add(new HostRecord(id, o.optString("name", "Host"), o.optString("localAddress", "")));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static void upsert(Context c, HostRecord rec) {
        if (rec == null || rec.remoteId == null || rec.remoteId.length() != 9) return;
        List<HostRecord> all = load(c);
        boolean found = false;
        for (HostRecord h : all) {
            if (h.remoteId.equals(rec.remoteId)) {
                h.name = rec.name;
                h.localAddress = rec.localAddress;
                found = true;
                break;
            }
        }
        if (!found) all.add(rec);
        save(c, all);
    }

    public static void remove(Context c, String remoteId) {
        List<HostRecord> all = load(c);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).remoteId.equals(remoteId)) all.remove(i);
        }
        save(c, all);
    }

    private static void save(Context c, List<HostRecord> all) {
        JSONArray a = new JSONArray();
        try {
            for (HostRecord h : all) {
                JSONObject o = new JSONObject();
                o.put("remoteId", h.remoteId);
                o.put("name", h.name);
                o.put("localAddress", h.localAddress);
                a.put(o);
            }
            prefs(c).edit().putString(KEY, a.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
