package com.ivolume.ivolume;

import android.accessibilityservice.AccessibilityService;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

import androidx.annotation.RequiresApi;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

//import com.ivolume.ivolume.VolumeUpdater;

@RequiresApi(api = Build.VERSION_CODES.R)
public class MainService extends AccessibilityService {

    static final public String ACTION_RECORD_MSG = "com.ivolume.ivolume.mainservice.record_msg";
    static final public String EXTRA_MSG = "com.ivolume.ivolume.mainservice.msg";
    static final public String CONTEXT_LOG_TAG = "mainservice.getcontext.log";

    private final AtomicInteger mLogID = new AtomicInteger(0);
    private final IntUnaryOperator operator = x -> (x < 999) ? (x + 1) : 0;

    // TODO add gps map table, app map table

    // listening
    final Uri[] listenedURIs = {
            Settings.System.CONTENT_URI,
            Settings.Global.CONTENT_URI,
    };
    final String[] listenedActions = {
            Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED,
            Intent.ACTION_HEADSET_PLUG,
            Intent.ACTION_USER_BACKGROUND,
            Intent.ACTION_USER_FOREGROUND,
            // Bluetooth related
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_ACL_DISCONNECTED,
            BluetoothDevice.ACTION_ALIAS_CHANGED,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_NAME_CHANGED,
    };

    // recording related
    String filename = "log.tsv";
    FileWriter writer;
    int brightness;
    static final HashMap<String, Integer> volume = new HashMap<>();

    static {
        // speaker
        volume.put("volume_music_speaker", 0);
        volume.put("volume_ring_speaker", 0);
        volume.put("volume_alarm_speaker", 0);
        volume.put("volume_voice_speaker", 0);
        volume.put("volume_tts_speaker", 0);
        // headset
        volume.put("volume_music_headset", 0);
        volume.put("volume_voice_headset", 0);
        volume.put("volume_tts_headset", 0);
        // headphone
        volume.put("volume_music_headphone", 0);
        volume.put("volume_voice_headphone", 0);
        volume.put("volume_tts_headphone", 0);
        // Bluetooth A2DP
        volume.put("volume_music_bt_a2dp", 0);
        volume.put("volume_voice_bt_a2dp", 0);
        volume.put("volume_tts_bt_a2dp", 0);
    }

    String packageName = "";

    Context context;
    LocalBroadcastManager localBroadcastManager;
    VolumeUpdater volumeUpdater;

    //监测APP
    private static String CurrentPackage; //当前app
    static Map<String, Integer> AppPackageMap = new HashMap<String, Integer>(){{
        put("com.tencent.wemeet.app", 0); //微信
        put("com.tencent.mm", 1);  //腾讯会议
        put("tv.danmaku.bili", 2);  //b站
        put("com.netease.cloudmusic", 3);  //网易云
        put("cn.ledongli.ldl", 4);  //乐动力
    }};


    // TODO judge whether context changed, if so:
    // 1. call all four context getter
    // 2. call volume updater

    void jsonSilentPut(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            JSONObject json = new JSONObject();
            String action = intent.getAction();

            // get extra paras into JSON string
            Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object obj = JSONObject.wrap(extras.get(key));
                    if (obj == null) {
                        obj = JSONObject.wrap(extras.get(key).toString());
                    }
                    jsonSilentPut(json, key, obj);
                }
            }

            // record additional information for some special actions
            switch (action) {
                case Intent.ACTION_CONFIGURATION_CHANGED:
                    Configuration config = getResources().getConfiguration();
                    jsonSilentPut(json, "configuration", config.toString());
                    jsonSilentPut(json, "orientation", config.orientation);
                    break;
                case Intent.ACTION_SCREEN_OFF:
                case Intent.ACTION_SCREEN_ON:
                    // ref: https://stackoverflow.com/a/17348755/11854304
                    DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                    if (dm != null) {
                        Display[] displays = dm.getDisplays();
                        int[] states = new int[displays.length];
                        for (int i = 0; i < displays.length; i++) {
                            states[i] = displays[i].getState();
                        }
                        jsonSilentPut(json, "displays", states);
                    }
                    break;
            }

            jsonSilentPut(json, "package", packageName);

            // record data
            record("BroadcastReceive", action, "", json.toString());
        }
    };

    // ref: https://stackoverflow.com/a/67355428/11854304
    ContentObserver contentObserver = new ContentObserver(new Handler()) {

        @Override
        public void onChange(boolean selfChange) {
            onChange(selfChange, null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            JSONObject json = new JSONObject();
            String key;
            int value = 0;
            String tag = "";

            if (uri == null) {
                key = "uri_null";
            } else {
                key = uri.toString();
                String database_key = uri.getLastPathSegment();
                String inter = uri.getPathSegments().get(0);
                if ("system".equals(inter)) {
                    value = Settings.System.getInt(getContentResolver(), database_key, value);
                    tag = Settings.System.getString(getContentResolver(), database_key);
                } else if ("global".equals(inter)) {
                    value = Settings.Global.getInt(getContentResolver(), database_key, value);
                    tag = Settings.Global.getString(getContentResolver(), database_key);
                }

                // record special information
                if (Settings.System.SCREEN_BRIGHTNESS.equals(database_key)) {
                    // record brightness value difference and update
                    int diff = value - brightness;
                    jsonSilentPut(json, "diff", diff);
                    brightness = value;
                    // record brightness mode
                    int mode = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, -1);
                    if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                        jsonSilentPut(json, "mode", "man");
                    } else if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                        jsonSilentPut(json, "mode", "auto");
                    } else {
                        jsonSilentPut(json, "mode", "unknown");
                    }
                }
                if (database_key.startsWith("volume_")) {
                    if (!volume.containsKey(database_key)) {
                        // record new volume value
                        volume.put(database_key, value);
                    }
                    // record volume value difference and update
                    int diff = value - volume.put(database_key, value);
                    jsonSilentPut(json, "diff", diff);
                }
            }

            jsonSilentPut(json, "package", packageName);

            // record data
            record("ContentChange", key, tag, json.toString());

//            volumeUpdater.update();
        }
    };

    public MainService() {
        volumeUpdater = new VolumeUpdater(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        CurrentPackage = "";
        initialize();
    }

    @Override
    public void onDestroy() {
        terminate();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        if (pkg != null) {
            packageName = event.getPackageName().toString();
        }

        int type=event.getEventType();
        //监测app变化
        if(type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            String tmpPackage = event.getPackageName()==null? "": event.getPackageName().toString();
            if(!tmpPackage.equals(CurrentPackage)){
                //当前app包名改变时
                //只针对AppPackageMap的5个app进行处理，忽略其他包
                if(AppPackageMap.containsKey(tmpPackage)) {
                    CurrentPackage = tmpPackage;
                    int cur_index = AppPackageMap.get(CurrentPackage);
                    Log.d(CONTEXT_LOG_TAG, "CurrentPackage changed, name:" + CurrentPackage
                    + ", index:" + Integer.toString(cur_index));
                    //TODO get other context & update volume

                }
            }
        }
    }

    @Override
    public void onInterrupt() {

    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        JSONObject json = new JSONObject();
        jsonSilentPut(json, "code", event.getKeyCode());
        jsonSilentPut(json, "action", event.getAction());
        jsonSilentPut(json, "source", event.getSource());
        jsonSilentPut(json, "eventTime", event.getEventTime());
        jsonSilentPut(json, "downTime", event.getDownTime());
        jsonSilentPut(json, "package", packageName);
        jsonSilentPut(json, "keycodeString", KeyEvent.keyCodeToString(event.getKeyCode()));

        // TODO remove this code
        if (volumeUpdater != null) {
            // TODO change param to real context
            volumeUpdater.update(0, 0, false, 0);
        } else {
            jsonSilentPut(json, "error", "no instance");
        }

        // TODO remove this code
        /* =========== Demo code begin =========== */
        //如何获取当前app
        Integer cur_app_index = getApp();
        Log.d(CONTEXT_LOG_TAG, "get app"+ cur_app_index);
        //在前台中显示
        JSONObject json_app = new JSONObject();
        jsonSilentPut(json_app, "cur_app",  cur_app_index);
        record("GET APP", "KeyEvent://" + event.getAction() + "/" + event.getKeyCode(), "", json_app.toString());
        /* =========== Demo code end =========== */

        record("KeyEvent", "KeyEvent://" + event.getAction() + "/" + event.getKeyCode(), "", json.toString());
        return super.onKeyEvent(event);
    }

    void initialize() {
        // register broadcast receiver
        IntentFilter filter = new IntentFilter();
        for (String action : listenedActions) {
            filter.addAction(action);
        }
        registerReceiver(broadcastReceiver, filter);

        // register content observer
        for (Uri uri : listenedURIs) {
            getContentResolver().registerContentObserver(uri, true, contentObserver);
        }

        // recording related
        try {
            File file;
            if (isExternalStorageWritable()) {
                file = new File(getExternalFilesDir(null), filename);
            } else {
                file = new File(getFilesDir(), filename);
            }
            // append to file
            writer = new FileWriter(file, true);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // record all current values
        record_all();
    }

    void terminate() {
        // unregister broadcast receiver
        unregisterReceiver(broadcastReceiver);
        // unregister content observer
        getContentResolver().unregisterContentObserver(contentObserver);

        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    // Checks if a volume containing external storage is available
    // for read and write.
    private boolean isExternalStorageWritable() {
        return Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);
    }

    void record_all() {
        JSONObject json = new JSONObject();

        // store brightness
        brightness = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 0);
        jsonSilentPut(json, "brightness", brightness);

        // store volumes
        volume.replaceAll((k, v) -> Settings.System.getInt(getContentResolver(), k, 0));
        volume.forEach((k, v) -> jsonSilentPut(json, k, v));

        // store configuration and orientation
        Configuration config = getResources().getConfiguration();
        jsonSilentPut(json, "configuration", config.toString());
        jsonSilentPut(json, "orientation", config.orientation);

        // store system settings
        jsonPutSettings(json, "system", Settings.System.class);

        // store global settings
        jsonPutSettings(json, "global", Settings.Global.class);

        // store secure settings
        jsonPutSettings(json, "secure", Settings.Secure.class);

        // record
        record("static", "start", "", json.toString());
    }

    void jsonPutSettings(JSONObject json, String key, Class<?> c) {
        JSONArray jsonArray = new JSONArray();
        Field[] fields_glb = c.getFields();
        for (Field f : fields_glb) {
            if (Modifier.isStatic(f.getModifiers())) {
                try {
                    String name = f.getName();
                    Object obj = f.get(null);
                    if (obj != null) {
                        String database_key = obj.toString();
                        Method method = c.getMethod("getString", ContentResolver.class, String.class);
                        String value_s = (String) method.invoke(null, getContentResolver(), database_key);
                        jsonArray.put(new JSONArray().put(name).put(database_key).put(value_s));
                    }
                } catch (IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
                    e.printStackTrace();
                }
            }
        }
        jsonSilentPut(json, key, jsonArray);
    }

    private int incLogID() {
        return mLogID.getAndUpdate(operator);
    }

    // record data to memory and file
    public void record(String type, String action, String tag, String other) {
        long cur_timestamp = System.currentTimeMillis();
        // record to memory
        String[] paras = {Long.toString(cur_timestamp), Integer.toString(incLogID()), type, action, tag, other};
        String line = String.join("\t", paras);
        // record to file
        if (writer != null) {
            try {
                writer.write(line + "\n");
                writer.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // broadcast to update UI
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA);
        String cur_datetime = format.format(new Date(cur_timestamp));
        paras[0] = " -------- " + cur_datetime + " -------- ";
        broadcast(String.join("\n", paras));
    }

    // send broadcast to notify
    private void broadcast(String msg) {
        Intent intent = new Intent(ACTION_RECORD_MSG);
        if (msg != null)
            intent.putExtra(EXTRA_MSG, msg);
        localBroadcastManager.sendBroadcast(intent);
    }

    ///<summary>获得当前app信息
    ///返回0-4
    // 如果当前app不在5个的范围内，返回5
    public Integer getApp(){
        if(AppPackageMap.containsKey(CurrentPackage))
            return AppPackageMap.get(CurrentPackage);
        return 5;
    }
}
