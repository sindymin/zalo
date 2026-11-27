package com.example.zaloweb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.HashSet;
import java.util.Set;

/** Cứ 10 phút: mở WebView ẩn, tải Zalo Web (phiên đã đăng nhập), đọc các cuộc trò chuyện chưa đọc và báo tên + nội dung. */
public class CheckReceiver extends BroadcastReceiver {
    private static final String CH = "zalo_check";
    private static final long WAIT_MS = 25000;
    private static final long TIMEOUT_MS = 70000;

    // Đọc danh sách hội thoại có huy hiệu số chưa đọc -> {name, msg, count}.
    // Nếu Zalo đổi giao diện, chỉnh các selector ở NAME_SEL / MSG_SEL.
    private static final String JS =
        "(function(){try{" +
        "var NAME_SEL='[class*=\"title__name\"],[class*=\"conv-name\"],[class*=\"name\"]';" +
        "var MSG_SEL='[class*=\"body__main\"],[class*=\"last-msg\"],[class*=\"preview\"],[class*=\"body\"]';" +
        "var TIME=/^(\\d{1,2}:\\d{2}|\\d+\\s?(p|ph|phút|h|giờ|g|d|ngày)|hôm qua|vừa xong|now)$/i;" +
        "var t=document.title||'';var m=t.match(/\\((\\d+)\\+?\\)/);var total=m?parseInt(m[1],10):0;" +
        "var items=[];var seen={};" +
        "var c=document.querySelectorAll('[class*=\"unread\"],[class*=\"badge\"],[class*=\"Badge\"]');" +
        "for(var i=0;i<c.length;i++){var b=c[i];var bt=(b.textContent||'').trim();" +
        " if(!/^\\d{1,3}\\+?$/.test(bt))continue;" +
        " var el=b,row=null;" +
        " for(var d=0;d<8&&el.parentElement;d++){el=el.parentElement;" +
        "  var ls=(el.innerText||'').split('\\n').map(function(s){return s.trim();}).filter(Boolean);" +
        "  if(ls.length>=3&&ls.length<=10){row=el;break;}}" +
        " if(!row)continue;" +
        " var name='',msg='';" +
        " var ne=row.querySelector(NAME_SEL);if(ne)name=(ne.innerText||'').trim();" +
        " var me=row.querySelector(MSG_SEL);if(me)msg=(me.innerText||'').trim();" +
        " var lines=(row.innerText||'').split('\\n').map(function(s){return s.trim();})" +
        "  .filter(function(s){return s&&s!==bt&&!TIME.test(s);});" +
        " if(!name)name=lines[0]||'';" +
        " if(!msg||msg===name){msg=lines.slice(name===lines[0]?1:0).join(' ');}" +
        " if(msg.indexOf(name)===0&&msg.length>name.length+1&&lines.length<2)msg=msg.substring(name.length);" +
        " if(!name||seen[name])continue;seen[name]=1;" +
        " items.push({name:name,msg:msg.substring(0,300),count:parseInt(bt,10)||1});}" +
        "var login=!!document.querySelector('input[type=\"tel\"],[class*=\"qr\"],[class*=\"login\"]')" +
        "||location.href.indexOf('id.zalo.me')>=0||location.href.indexOf('/login')>=0;" +
        "return JSON.stringify({total:total,items:items,login:login&&total===0&&items.length===0});" +
        "}catch(e){return JSON.stringify({total:0,items:[],login:false,err:String(e)});}})()";

    @Override public void onReceive(final Context ctx, Intent intent) {
        Scheduler.scheduleNext(ctx);
        final Context app = ctx.getApplicationContext();
        PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        final PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zaloweb:check");
        wl.acquire(TIMEOUT_MS + 5000);

        final Handler h = new Handler(Looper.getMainLooper());
        final WebView w = new WebView(app);
        final boolean[] done = {false};
        final Runnable finish = new Runnable() {
            @Override public void run() {
                if (done[0]) return;
                done[0] = true;
                try { w.stopLoading(); w.destroy(); } catch (Throwable ignored) {}
                if (wl.isHeld()) wl.release();
            }
        };
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(MainActivity.UA);
        w.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                h.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (done[0]) return;
                        w.evaluateJavascript(JS, new ValueCallback<String>() {
                            @Override public void onReceiveValue(String val) {
                                handle(app, val);
                                finish.run();
                            }
                        });
                    }
                }, WAIT_MS);
            }
        });
        h.postDelayed(finish, TIMEOUT_MS);
        w.loadUrl(MainActivity.URL);
    }

    private void handle(Context c, String raw) {
        try {
            if (raw == null || raw.equals("null")) return;
            Object v = new JSONTokener(raw).nextValue();      // giải mã chuỗi JSON (gồm \uXXXX)
            JSONObject o = new JSONObject(String.valueOf(v));
            SharedPreferences sp = c.getSharedPreferences("zalo", Context.MODE_PRIVATE);

            if (o.optBoolean("login", false)) {
                notify(c, 2, "Zalo Web", "Phiên đăng nhập hết hạn. Mở app để đăng nhập lại.");
                return;
            }
            JSONArray items = o.optJSONArray("items");
            int total = o.optInt("total", 0);
            SharedPreferences.Editor ed = sp.edit();
            Set<String> oldNames = sp.getStringSet("names", new HashSet<String>());
            Set<String> curNames = new HashSet<String>();

            if (items != null && items.length() > 0) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    String name = it.optString("name");
                    String msg = it.optString("msg");
                    int cnt = it.optInt("count", 1);
                    String sig = cnt + "|" + msg;
                    curNames.add(name);
                    if (!sig.equals(sp.getString("seen_" + name, null))) {
                        String text = msg.isEmpty() ? "Có " + cnt + " tin nhắn mới" : msg;
                        String title = cnt > 1 ? name + " (" + cnt + ")" : name;
                        notify(c, 1000 + (name.hashCode() & 0xffff), title, text);
                    }
                    ed.putString("seen_" + name, sig);
                }
            } else if (total > 0 && total != sp.getInt("last_total", 0)) {
                // Không đọc được chi tiết -> báo tổng số
                notify(c, 1, "Zalo", "Bạn có " + total + " tin nhắn chưa đọc");
            }
            for (String old : oldNames) if (!curNames.contains(old)) ed.remove("seen_" + old);
            ed.putStringSet("names", curNames);
            ed.putInt("last_total", total);
            ed.apply();
        } catch (Throwable ignored) {}
    }

    private void notify(Context c, int id, String title, String text) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CH, "Tin nhắn Zalo", NotificationManager.IMPORTANCE_HIGH));
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), flags);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH) : new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(title).setContentText(text)
         .setStyle(new Notification.BigTextStyle().bigText(text))
         .setContentIntent(pi).setAutoCancel(true);
        if (Build.VERSION.SDK_INT < 26) b.setDefaults(Notification.DEFAULT_ALL).setPriority(Notification.PRIORITY_HIGH);
        nm.notify(id, b.build());
    }
}
