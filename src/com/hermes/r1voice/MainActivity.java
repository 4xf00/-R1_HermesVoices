package com.hermes.r1voice;

import android.app.Activity;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import java.io.*;
import java.net.URLDecoder;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.hermes.r1voice.ChinesePinyin;
public class MainActivity extends Activity {
    private static final String TAG = "HermesVoice";
    private static final int WEB_PORT = 6060;

    // Config
    private String hermesUrl = "http://YOUR_HERMES_IP:8748";
    private String sttUrl = "http://YOUR_MAC_IP:9000";
    private String authUser = "";
    private String authPass = "";
    private String wakeWord = "";
    private int wakeTimeoutSec = 3;
    private int continuousPauseMs = 2000;
    private int conversationTimeoutMs = 30000;
    private boolean continuousMode = false;
    private float kwsThreshold = 0.1f;

    // Audio
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = android.media.AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = android.media.AudioFormat.ENCODING_PCM_16BIT;
    private int minBufferSize;
    private static final int VAD_THRESHOLD = 50;
    private static final int SILENCE_TIMEOUT_MS = 2000;

    // State machine
    private static final int STATE_IDLE = 0;
    private static final int STATE_LISTENING = 1;
    private static final int STATE_PROCESSING = 2;
    private static final int STATE_SPEAKING = 3;
    private volatile int state = STATE_IDLE;
    private static final String[] EXIT_COMMANDS = {"退出", "结束", "再见", "拜拜", "关闭", "停止", "退下", "退下吧"};

    // Runtime
    private volatile boolean running = true;
    private String authToken = null;
    private String sessionId = null;
    private long lastSpeechTime = 0;
    private long startTime = 0;
    private int totalConversations = 0;
    private String lastUserText = "";
    private String lastAiText = "";
    private TextView statusText, responseText, serverText;
    private MiniHttpServer webServer;

    // KWS (Keyword Spotting)
    private KeywordSpotter kws = null;
    private OnlineStream kwsStream = null;
    private static final int KWS_MODEL_SAMPLE_RATE = 16000;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        statusText = (TextView) findViewById(R.id.statusText);
        responseText = (TextView) findViewById(R.id.responseText);
        serverText = (TextView) findViewById(R.id.serverText);
        minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        startTime = System.currentTimeMillis();
        loadConfig();
        ChinesePinyin.init(this);
        serverText.setText("URL: " + hermesUrl + "\nSTT: " + sttUrl + "\nWake: " + (wakeWord.isEmpty() ? "(always on)" : wakeWord));
        flog("=== HermesVoice v6 ===");
        flog("Web UI: http://<device-ip>:" + WEB_PORT);
        startWebServer();
        new Thread() {
            public void run() {
                authToken = loginAndGetToken();
                flog("Token: " + (authToken != null ? "OK" : "FAIL"));
            }
        }.start();
        // Initialize KWS synchronously — must be ready before main loop
        initKws();
        new Thread() {
            public void run() { mainLoop(); }
        }.start();
    }

    private void loadConfig() {
        try {
            File f = new File("/sdcard/hermes_config.properties");
            if (f.exists()) {
                Properties p = new Properties();
                p.load(new FileInputStream(f));
                String v;
                v = p.getProperty("hermes.url"); if (v != null) hermesUrl = v;
                v = p.getProperty("stt.url"); if (v != null) sttUrl = v;
                v = p.getProperty("auth.user"); if (v != null) authUser = v;
                v = p.getProperty("auth.pass"); if (v != null) authPass = v;
                v = p.getProperty("wake.word"); if (v != null) wakeWord = decodeUnicode(v.trim());
                v = p.getProperty("wake.timeout"); if (v != null) try { wakeTimeoutSec = Integer.parseInt(v.trim()); } catch (Exception ex) {}
                v = p.getProperty("continuous.pause"); if (v != null) try { continuousPauseMs = Integer.parseInt(v.trim()) * 1000; } catch (Exception ex) {}
                v = p.getProperty("continuous.timeout"); if (v != null) try { conversationTimeoutMs = Integer.parseInt(v.trim()) * 1000; } catch (Exception ex) {}
                v = p.getProperty("continuous.mode"); if (v != null) continuousMode = "true".equals(v.trim());
                v = p.getProperty("kws.threshold"); if (v != null) try { kwsThreshold = Float.parseFloat(v.trim()); } catch (Exception ex) {}
                flog("Config loaded: user=" + authUser + " wake=" + wakeWord + " continuous=" + continuousMode + " threshold=" + kwsThreshold);
            }
        } catch (Exception e) { flog("Config err: " + e); }
    }


    // ========== Web Server ==========
    private void startWebServer() {
        try {
            webServer = new MiniHttpServer(WEB_PORT, new MiniHttpServer.RequestHandler() {
                public String handle(String path, String method, String body) throws Exception {
                    if (path.equals("/api/status")) return getStatusJson();
                    if (path.equals("/api/save") && method.equals("POST")) return handleSave(body);
                    if (path.equals("/api/logs")) return getLogs();
                    if (path.equals("/control")) return getControlPage();
                    return getConfigPage();
                }
            });
            webServer.start();
            flog("Web server started on :" + WEB_PORT);
        } catch (Exception e) { flog("Web server err: " + e); }
    }


    private String getStatusJson() {
        long uptime = (System.currentTimeMillis() - startTime) / 1000;
        String st = state == STATE_IDLE ? "idle" : state == STATE_LISTENING ? "listening" : state == STATE_PROCESSING ? "processing" : "speaking";
        return "{\"state\":\"" + st + "\",\"uptime\":" + uptime
            + ",\"conversations\":" + totalConversations
            + ",\"wakeWord\":\"" + escJson(wakeWord) + "\""
            + ",\"lastUser\":\"" + escJson(lastUserText) + "\""
            + ",\"lastAi\":\"" + escJson(lastAiText.substring(0, Math.min(200, lastAiText.length()))) + "\""
            + ",\"tokenOk\":" + (authToken != null)
            + ",\"continuousMode\":" + continuousMode
            + ",\"hermesUrl\":\"" + escJson(hermesUrl) + "\""
            + ",\"sttUrl\":\"" + escJson(sttUrl) + "\""
            + ",\"authUser\":\"" + escJson(authUser) + "\""
            + ",\"authPass\":\"" + escJson(authPass) + "\""
            + ",\"wakeTimeout\":" + wakeTimeoutSec
            + ",\"continuousPause\":" + (continuousPauseMs / 1000)
            + ",\"continuousTimeout\":" + (conversationTimeoutMs / 1000)
            + ",\"kwsThreshold\":\"" + kwsThreshold + "\""
            + "}";
    }

    private String handleSave(String body) {
        try {
            Map<String, String> params = parseFormData(body);
            String v;
            v = params.get("hermesUrl"); if (v != null && !v.isEmpty()) hermesUrl = v;
            v = params.get("sttUrl"); if (v != null && !v.isEmpty()) sttUrl = v;
            v = params.get("authUser"); if (v != null && !v.isEmpty()) authUser = v;
            String newPass = params.containsKey("authPass") ? params.get("authPass") : "";
            if (!newPass.isEmpty() && !newPass.equals("***")) authPass = newPass;
            v = params.get("wakeWord"); if (v != null && !v.isEmpty()) {
                if (!v.equals(wakeWord)) {
                    wakeWord = v;
                    flog("Wake word changed to: " + wakeWord);
                    new Thread() { public void run() { reloadKws(wakeWord); } }.start();
                }
            }
            try { wakeTimeoutSec = Integer.parseInt(params.containsKey("wakeTimeout") ? params.get("wakeTimeout") : String.valueOf(wakeTimeoutSec)); } catch (Exception e) {}
            try { continuousPauseMs = Integer.parseInt(params.containsKey("continuousPause") ? params.get("continuousPause") : String.valueOf(continuousPauseMs / 1000)) * 1000; } catch (Exception e) {}
            try { conversationTimeoutMs = Integer.parseInt(params.containsKey("continuousTimeout") ? params.get("continuousTimeout") : String.valueOf(conversationTimeoutMs / 1000)) * 1000; } catch (Exception e) {}
            if (params.containsKey("continuousMode")) continuousMode = "true".equals(params.get("continuousMode"));
            try { kwsThreshold = Float.parseFloat(params.containsKey("kwsThreshold") ? params.get("kwsThreshold") : String.valueOf(kwsThreshold)); } catch (Exception e) {}
            saveConfig();
            // Reload KWS with current settings
            new Thread() { public void run() { reloadKws(wakeWord); } }.start();
            // Re-login with new credentials
            new Thread() {
                public void run() {
                    authToken = loginAndGetToken();
                    flog("Token refreshed: " + (authToken != null ? "OK" : "FAIL"));
                }
            }.start();
            return "{\"ok\":true,\"msg\":\"Config saved! Changes take effect immediately.\"}";
        } catch (Exception e) {
            return "{\"ok\":false,\"msg\":\"" + escJson(e.toString()) + "\"}";
        }
    }

    private void saveConfig() {
        try {
            FileWriter fw = new FileWriter("/sdcard/hermes_config.properties");
            fw.write("hermes.url=" + hermesUrl + "\n");
            fw.write("stt.url=" + sttUrl + "\n");
            fw.write("auth.user=" + authUser + "\n");
            fw.write("auth.pass=" + authPass + "\n");
            fw.write("wake.word=" + encodeUnicode(wakeWord) + "\n");
            fw.write("wake.timeout=" + wakeTimeoutSec + "\n");
            fw.write("continuous.pause=" + (continuousPauseMs / 1000) + "\n");
            fw.write("continuous.timeout=" + (conversationTimeoutMs / 1000) + "\n");
            fw.write("continuous.mode=" + continuousMode + "\n");
            fw.write("kws.threshold=" + kwsThreshold + "\n");
            fw.close();
            flog("Config saved to /sdcard/hermes_config.properties");
        } catch (Exception e) { flog("Save err: " + e); }
    }

    private String getLogs() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/sdcard/hermes_voice.log"));
            StringBuilder sb = new StringBuilder();
            String line; int count = 0;
            while ((line = br.readLine()) != null) { sb.append(line).append("\n"); count++; if (count > 100) break; }
            br.close();
            return sb.toString();
        } catch (Exception e) { return "No logs yet"; }
    }

    private String getControlPage() {
        try {
            InputStream is = getResources().openRawResource(R.raw.r1_control);
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "<html><body><h1>Control page not found</h1></body></html>";
        }
    }

    private String getConfigPage() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<title>HermesVoice</title><style>"
            + "*{margin:0;padding:0;box-sizing:border-box}"
            + "body{font-family:-apple-system,sans-serif;background:#0f172a;color:#e2e8f0;padding:12px;max-width:480px;margin:0 auto}"
            + "h1{font-size:18px;text-align:center;color:#38bdf8;margin:8px 0 4px}"
            + ".ver{text-align:center;font-size:11px;color:#475569;margin-bottom:10px}"
            // Dashboard grid - fixed position 2-column
            + ".dash{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-bottom:12px}"
            + ".ditem{background:#1e293b;border-radius:10px;padding:10px 12px;text-align:center}"
            + ".ditem .dl{font-size:11px;color:#64748b;margin-bottom:2px}"
            + ".ditem .dv{font-size:16px;font-weight:700;color:#e2e8f0}"
            + ".ditem .dv.green{color:#22c55e}.ditem .dv.yellow{color:#eab308}.ditem .dv.blue{color:#3b82f6}.ditem .dv.red{color:#ef4444}"
            + ".ditem.wide{grid-column:span 2}"
            // Connection bar
            + ".conn{text-align:center;padding:6px;border-radius:8px;margin-bottom:10px;font-size:12px;font-weight:600}"
            + ".conn.on{background:#0a2a1a;color:#22c55e}.conn.off{background:#2a0a0a;color:#ef4444}"
            // Config form
            + ".card{background:#1e293b;border-radius:12px;padding:14px;margin-bottom:10px}"
            + ".card h2{font-size:13px;color:#94a3b8;margin-bottom:10px;border-bottom:1px solid #334155;padding-bottom:6px}"
            + "label{display:block;font-size:12px;color:#64748b;margin:8px 0 3px}"
            + "input[type=text],input[type=password],input[type=number]{width:100%;padding:8px;border-radius:6px;border:1px solid #334155;background:#0f172a;color:#e2e8f0;font-size:13px}"
            + ".row2{display:grid;grid-template-columns:1fr 1fr;gap:8px}"
            + ".row2 label{margin-top:6px}"
            + ".row2 input{width:100%}"
            + ".ck{display:flex;align-items:center;gap:6px;margin-top:10px}"
            + ".ck input{width:16px;height:16px}"
            + ".ck label{margin:0;font-size:13px;color:#e2e8f0}"
            + "button.save{width:100%;padding:10px;border-radius:8px;border:none;font-size:14px;font-weight:600;cursor:pointer;margin-top:10px;background:#22c55e;color:#fff}"
            + "button.save:active{background:#16a34a}"
            + "button.secondary{width:100%;padding:8px;border-radius:8px;border:1px solid #334155;background:transparent;color:#94a3b8;font-size:12px;cursor:pointer;margin-top:6px}"
            // Logs
            + ".log{background:#0a0a0f;border-radius:8px;padding:8px;font-size:10px;font-family:monospace;max-height:120px;overflow-y:auto;color:#64748b;white-space:pre-wrap;word-break:break-all;margin-top:8px}"
            + "#toast{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#22c55e;color:#fff;padding:8px 16px;border-radius:8px;display:none;font-size:13px;z-index:999}"
            + "</style></head><body>"

            + "<h1>\uD83E\uDD16 HermesVoice</h1>"
            + "<div class='ver'>v6 \u00b7 R1 \u667a\u80fd\u8bed\u97f3\u52a9\u624b</div>"

            // Dashboard grid - fixed position status
            + "<div class='dash'>"
            + "<div class='ditem'><div class='dl'>\u72b6\u6001</div><div class='dv' id='dvState'>-</div></div>"
            + "<div class='ditem'><div class='dl'>\u8fd0\u884c\u65f6\u957f</div><div class='dv' id='dvUptime'>-</div></div>"
            + "<div class='ditem'><div class='dl'>\u5bf9\u8bdd\u6b21\u6570</div><div class='dv' id='dvConvos'>0</div></div>"
            + "<div class='ditem'><div class='dl'>\u5524\u9192\u8bcd</div><div class='dv' id='dvWake'>-</div></div>"
            + "<div class='ditem'><div class='dl'>\u8fde\u7eed\u6a21\u5f0f</div><div class='dv' id='dvCont'>-</div></div>"
            + "<div class='ditem'><div class='dl'>Token</div><div class='dv' id='dvToken'>-</div></div>"
            + "</div>"

            // Connection bar
            + "<div class='conn off' id='connBar'>\u25cf \u672a\u8fde\u63a5</div>"

            // Last user/AI messages
            + "<div class='card'><h2>\u6700\u8fd1\u5bf9\u8bdd</h2>"
            + "<div style='font-size:12px;margin-bottom:4px;color:#64748b'>\u7528\u6237\uff1a<span id='dvLastUser' style='color:#e2e8f0'>-</span></div>"
            + "<div style='font-size:12px;color:#64748b'>AI\uff1a<span id='dvLastAi' style='color:#94a3b8;word-break:break-all'>-</span></div>"
            + "</div>"

            // Config form
            + "<div class='card'><h2>\u914d\u7f6e</h2>"
            + "<label>Hermes Studio URL</label><input type='text' id='hermesUrl' placeholder='http://192.168.1.100:8748'>"
            + "<label>STT \u670d\u52a1\u5668 URL</label><input type='text' id='sttUrl' placeholder='http://192.168.1.100:9000'>"
            + "<div class='row2'><div><label>\u7528\u6237\u540d</label><input type='text' id='authUser'></div><div><label>\u5bc6\u7801</label><input type='password' id='authPass'></div></div>"
            + "<div class='row2'><div><label>\u5524\u9192\u8bcd</label><input type='text' id='wakeWord'></div><div><label>KWS \u7075\u654f\u5ea6</label><input type='number' id='kwsThreshold' min='0.01' max='1.0' step='0.01'></div></div>"
            + "<div class='row2'><div><label>\u5524\u9192\u8d85\u65f6(\u79d2)</label><input type='number' id='wakeTimeout'></div><div><label>\u95f4\u9694(\u79d2)</label><input type='number' id='continuousPause'></div></div>"
            + "<label>\u65e0\u8bed\u97f3\u8d85\u65f6(\u79d2)</label><input type='number' id='continuousTimeout'>"
            + "<div class='ck'><input type='checkbox' id='continuousMode'><label for='continuousMode'>\u6301\u7eed\u5bf9\u8bdd\u6a21\u5f0f</label></div>"
            + "<button class='save' onclick='saveConfig()'>\u4fdd\u5b58\u914d\u7f6e</button>"
            + "<button class='secondary' onclick='testConnection()'>\u6d4b\u8bd5\u8fde\u63a5</button>"
            + "</div>"

            // Logs
            + "<div class='card'><h2>\u6700\u8fd1\u65e5\u5fd7</h2><div class='log' id='logs'>...</div></div>"
            + "<div id='toast'></div>"

            + "<script>"
            // i18n
            + "var L={cn:{idle:'\u5f85\u673a',listening:'\u76d1\u542c\u4e2d',processing:'\u5904\u7406\u4e2d',speaking:'\u64ad\u653e\u4e2d',"
            + "on:'\u5df2\u8fde\u63a5',off:'\u672a\u8fde\u63a5',"
            + "contOn:'\u5f00\u542f',contOff:'\u5173\u95ed',"
            + "tokenOk:'\u6b63\u5e38',tokenNo:'\u672a\u8ba4\u8bc1',"
            + "connected:'\u8fde\u63a5\u6210\u529f! Token \u6b63\u5e38',authFail:'\u8ba4\u8bc1\u5931\u8d25 - \u8bf7\u68c0\u67e5\u8d26\u53f7\u5bc6\u7801',"
            + "connFail:'\u8fde\u63a5\u5931\u8d25: ',saved:'\u914d\u7f6e\u5df2\u4fdd\u5b58!'},"
            + "en:{idle:'Idle',listening:'Listening',processing:'Processing',speaking:'Speaking',"
            + "on:'Connected',off:'Disconnected',"
            + "contOn:'On',contOff:'Off',"
            + "tokenOk:'OK',tokenNo:'No Auth',"
            + "connected:'Connected! Token OK',authFail:'Auth failed',"
            + "connFail:'Failed: ',saved:'Saved!' }};"
            + "var lang='cn';"
            + "function setLang(l){lang=l;localStorage.setItem('lang',l);}"
            + "function t(key){return L[lang][key]||L['cn'][key]||key;}"
            // API
            + "function loadStatus(){fetch('/api/status').then(r=>r.json()).then(d=>{"
            // State
            + "var se=document.getElementById('dvState');"
            + "if(d.state==='idle'){se.textContent=t('idle');se.className='dv yellow';}"
            + "else if(d.state==='listening'){se.textContent=t('listening');se.className='dv green';}"
            + "else if(d.state==='processing'){se.textContent=t('processing');se.className='dv blue';}"
            + "else{se.textContent=t('speaking');se.className='dv green';}"
            // Uptime
            + "var u=d.uptime,m=Math.floor(u/60),h=Math.floor(m/60);"
            + "document.getElementById('dvUptime').textContent=h>0?h+'\u5c0f\u65f6'+m%60+'\u5206':m+'\u5206'+u%60+'\u79d2';"
            // Convos
            + "document.getElementById('dvConvos').textContent=d.conversations;"
            // Wake word
            + "document.getElementById('dvWake').textContent=d.wakeWord||'-';"
            // Continuous mode
            + "var ce=document.getElementById('dvCont');"
            + "ce.textContent=d.continuousMode?t('contOn'):t('contOff');"
            + "ce.className=d.continuousMode?'dv green':'dv yellow';"
            // Token
            + "var te=document.getElementById('dvToken');"
            + "te.textContent=d.tokenOk?t('tokenOk'):t('tokenNo');"
            + "te.className=d.tokenOk?'dv green':'dv red';"
            // Connection bar
            + "var cb=document.getElementById('connBar');"
            + "if(d.tokenOk){cb.className='conn on';cb.textContent='\u25cf '+t('on');}"
            + "else{cb.className='conn off';cb.textContent='\u25cf '+t('off');}"
            // Last messages
            + "document.getElementById('dvLastUser').textContent=d.lastUser||'-';"
            + "document.getElementById('dvLastAi').textContent=d.lastAi||'-';"
            + "}).catch(()=>{})}"
            + "function loadConfig(){fetch('/api/status').then(r=>r.json()).then(d=>{"
            + "if(d.hermesUrl!==undefined)document.getElementById('hermesUrl').value=d.hermesUrl;"
            + "if(d.sttUrl!==undefined)document.getElementById('sttUrl').value=d.sttUrl;"
            + "if(d.authUser!==undefined)document.getElementById('authUser').value=d.authUser;"
            + "if(d.authPass!==undefined){document.getElementById('authPass').placeholder='***';}"
            + "if(d.wakeWord!==undefined)document.getElementById('wakeWord').value=d.wakeWord;"
            + "if(d.wakeTimeout!==undefined)document.getElementById('wakeTimeout').value=d.wakeTimeout;"
            + "if(d.continuousPause!==undefined)document.getElementById('continuousPause').value=d.continuousPause;"
            + "if(d.continuousTimeout!==undefined)document.getElementById('continuousTimeout').value=d.continuousTimeout;"
            + "if(d.continuousMode!==undefined)document.getElementById('continuousMode').checked=d.continuousMode;"
            + "if(d.kwsThreshold!==undefined)document.getElementById('kwsThreshold').value=d.kwsThreshold;"
            + "}).catch(()=>{})}"
            + "function loadLogs(){fetch('/api/logs').then(r=>r.text()).then(t=>{"
            + "var el=document.getElementById('logs');el.textContent=t;el.scrollTop=el.scrollHeight;"
            + "}).catch(()=>{})}"
            + "function saveConfig(){"
            + "var d=new URLSearchParams();"
            + "d.set('hermesUrl',document.getElementById('hermesUrl').value);"
            + "d.set('sttUrl',document.getElementById('sttUrl').value);"
            + "d.set('authUser',document.getElementById('authUser').value);"
            + "d.set('authPass',document.getElementById('authPass').value);"
            + "d.set('wakeWord',document.getElementById('wakeWord').value);"
            + "d.set('wakeTimeout',document.getElementById('wakeTimeout').value);"
            + "d.set('continuousPause',document.getElementById('continuousPause').value);"
            + "d.set('continuousTimeout',document.getElementById('continuousTimeout').value);"
            + "d.set('continuousMode',document.getElementById('continuousMode').checked);"
            + "d.set('kwsThreshold',document.getElementById('kwsThreshold').value);"
            + "fetch('/api/save',{method:'POST',body:d.toString(),headers:{'Content-Type':'application/x-www-form-urlencoded'}})"
            + ".then(r=>r.json()).then(d=>{showToast(t('saved'));loadConfig();}).catch(e=>showToast(t('connFail')+e));}"
            + "function testConnection(){fetch('/api/status').then(r=>r.json()).then(d=>{"
            + "showToast(d.tokenOk?t('connected'):t('authFail'));}).catch(e=>showToast(t('connFail')+e));}"
            + "function showToast(msg){var el=document.getElementById('toast');el.textContent=msg;el.style.display='block';setTimeout(()=>el.style.display='none',3000);}"
            + "var saved=localStorage.getItem('lang');if(saved)setLang(saved);else setLang('cn');"
            + "loadStatus();loadConfig();loadLogs();setInterval(()=>{loadStatus();loadLogs();},5000);"
            + "</script></body></html>";
    }


    // ========== Main State Machine ==========
    private long processingStartTime = 0;
    private static final long PROCESSING_TIMEOUT_MS = 60000;
    private void mainLoop() {
        while (running) {
            try {
                switch (state) {
                    case STATE_IDLE: doWakeWordDetection(); break;
                    case STATE_LISTENING: doListening(); break;
                    case STATE_PROCESSING:
                        if (processingStartTime == 0) processingStartTime = System.currentTimeMillis();
                        if (System.currentTimeMillis() - processingStartTime > PROCESSING_TIMEOUT_MS) {
                            flog(">>> PROCESSING timeout, forcing IDLE");
                            state = STATE_IDLE;
                        }
                        Thread.sleep(200);
                        break;
                    case STATE_SPEAKING: processingStartTime = 0; Thread.sleep(200); break;
                }
                if (state == STATE_IDLE || state == STATE_LISTENING) processingStartTime = 0;
            } catch (Exception e) {
                flog("Loop err: " + e);
                try { Thread.sleep(1000); } catch (InterruptedException ex) { return; }
            }
        }
    }

    // ========== KWS Initialization ==========
    private void initKws() {
        try {
            String modelDir = KeywordSpotter.findModelDir();
            flog("KWS model dir: " + modelDir);

            String encoder = modelDir + "/encoder-epoch-12-avg-2-chunk-16-left-64.onnx";
            String decoder = modelDir + "/decoder-epoch-12-avg-2-chunk-16-left-64.onnx";
            String joiner = modelDir + "/joiner-epoch-12-avg-2-chunk-16-left-64.onnx";
            String tokens = modelDir + "/tokens.txt";
            String keywords = modelDir + "/keywords.txt";

            OnlineTransducerModelConfig transducerConfig = new OnlineTransducerModelConfig(
                encoder, decoder, joiner);
            OnlineModelConfig modelConfig = new OnlineModelConfig(
                transducerConfig, tokens, 4, "cpu", "zipformer2");
            FeatureConfig featConfig = new FeatureConfig(16000, 80);
            KeywordSpotterConfig kwsConfig = new KeywordSpotterConfig(
                featConfig, modelConfig, 4, keywords, 3.0f, kwsThreshold, 1);

            kws = new KeywordSpotter(kwsConfig);
            kwsStream = kws.createStream("");
            flog("KWS initialized OK");
        } catch (Exception e) {
            flog("KWS init failed: " + e);
            kws = null;
        }
    }
    /** Reload KWS with new wake word — write keywords.txt and reinitialize */
    private synchronized void reloadKws(String newWakeWord) {
        try {
            String modelDir = "/mnt/internal_sd/sherpa-onnx-kws";
            String kwFile = modelDir + "/keywords.txt";
            String line = ChinesePinyin.toKeywordsLine(newWakeWord);
            if (line == null || line.isEmpty()) {
                flog("KWS reload skip: empty pinyin for '" + newWakeWord + "'");
                return;
            }
            // Write new keywords.txt
            FileWriter fw = new FileWriter(kwFile);
            fw.write(line + "\n");
            fw.close();
            flog("KWS keywords written: " + line);
            // Destroy old KWS
            if (kwsStream != null) { try { kwsStream = null; } catch (Exception e) {} }
            if (kws != null) { try { kws = null; } catch (Exception e) {} }
            // Reinitialize
            initKws();
            flog("KWS reloaded for: " + newWakeWord);
        } catch (Exception e) {
            flog("KWS reload err: " + e);
        }
    }

    // ========== Wake Word ==========
    private static final int WAKE_LISTEN_MS = 3000;
    private void doWakeWordDetection() {
        if (wakeWord.isEmpty()) { state = STATE_LISTENING; return; }
        flog("KWS state: kws=" + (kws != null ? "OK" : "null"));
        if (kws == null) { flog("KWS not ready, waiting..."); sleep(3000); return; }
        AudioRecord recorder = null;
        try {
            setAudioMode(true);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, minBufferSize * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) { sleep(3000); return; }
            recorder.startRecording();
            updateUI("待机 - 说 \"" + wakeWord + "\" 唤醒 (KWS)");
            byte[] buffer = new byte[minBufferSize];
            long t0 = System.currentTimeMillis();
            // Reset KWS stream for new detection cycle
            kws.reset(kwsStream);
            long lastLogTime = System.currentTimeMillis();
            int chunkCount = 0;
            while (state == STATE_IDLE && running) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) { flog("KWS read err: " + read); continue; }
                // Convert short PCM to float for KWS
                float[] audioFloat = new float[read / 2];
                long sum = 0;
                for (int i = 0; i < audioFloat.length; i++) {
                    short s = (short) ((buffer[i*2] & 0xFF) | (buffer[i*2+1] << 8));
                    audioFloat[i] = (float) s / 32768.0f;
                    sum += Math.abs(s);
                }
                kwsStream.acceptWaveform(audioFloat, KWS_MODEL_SAMPLE_RATE);
                chunkCount++;
                // Log amp every 500ms for diagnostics
                long now = System.currentTimeMillis();
                if (now - lastLogTime > 500) {
                    int amp = (int)(sum / audioFloat.length);
                    flog("KWS amp=" + amp + " chunks=" + chunkCount + " read=" + read);
                    lastLogTime = now;
                }
                // Check for keyword
                while (kws.isReady(kwsStream)) {
                    kws.decode(kwsStream);
                }
                KeywordSpotterResult result = kws.getResult(kwsStream);
                String keyword = result.getKeyword();
                if (keyword != null && keyword.length() > 0) {
                    flog("KWS detected: " + keyword);
                    recorder.stop(); recorder.release(); recorder = null;
                    state = STATE_LISTENING;
                    // Play ack async - don't block recording start
                    playWakeAckAsync();
                    return;
                }
                // Timeout after wakeTimeoutSec seconds of no detection
                if (System.currentTimeMillis() - t0 > wakeTimeoutSec * 1000L) {
                    recorder.stop(); recorder.release(); recorder = null;
                    sleep(200);
                    return;
                }
            }
            recorder.stop(); recorder.release(); recorder = null;
        } catch (Exception e) { flog("KWS wake err: " + e); safeRelease(recorder); }
    }

    // Fallback: STT-based wake word detection
    private void doWakeWordDetectionStt() {
        if (wakeWord.isEmpty()) { state = STATE_LISTENING; return; }
        AudioRecord recorder = null;
        try {
            setAudioMode(true);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, minBufferSize * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) { sleep(3000); return; }
            recorder.startRecording();
            updateUI("待机 - 说 \"" + wakeWord + "\" 唤醒 (STT)");
            byte[] buffer = new byte[minBufferSize];
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            long t0 = System.currentTimeMillis();
            boolean voiceDetected = false; int silentMs = 0;
            while (state == STATE_IDLE && running) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                int amp = getAmplitude(buffer, read);
                if (amp > VAD_THRESHOLD) { voiceDetected = true; silentMs = 0; buf.write(buffer, 0, read); }
                else if (voiceDetected) { silentMs += 100; buf.write(buffer, 0, read); if (silentMs >= 1500) break; }
                if (System.currentTimeMillis() - t0 > WAKE_LISTEN_MS && !voiceDetected) { recorder.stop(); recorder.release(); recorder = null; sleep(200); return; }
                if (buf.size() > SAMPLE_RATE * 2 * wakeTimeoutSec) break;
            }
            recorder.stop(); recorder.release(); recorder = null;
            if (buf.size() < 1600) return;
            byte[] wav = pcmToWav(buf.toByteArray(), SAMPLE_RATE, 1, 16);
            String stt = httpPostLocalStt(sttUrl + "/audio/transcriptions", wav, "wake.wav");
            String text = getJsonText(stt);
            flog("Wake: " + text);
            if (wakeWordMatch(text, wakeWord)) {
                flog(">>> WAKE WORD DETECTED (STT)");
                state = STATE_LISTENING;
                playWakeAck();
                sleep(1500);
            }
        } catch (Exception e) { flog("Wake err: " + e); safeRelease(recorder); }
    }

    // ========== Listening ==========
    private void doListening() {
        AudioRecord recorder = null;
        try {
            setAudioMode(true);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, minBufferSize * 2);
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) { sleep(3000); return; }
            recorder.startRecording();
            updateUI("监听中...");
            byte[] buffer = new byte[minBufferSize];
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            long t0 = System.currentTimeMillis();
            boolean voiceDetected = false; int silentMs = 0;
            // Adaptive noise floor: measure avg amp in first 1s
            long noiseSum = 0; int noiseCount = 0; int noiseFloor = 30;
            boolean noiseCalibrated = false;
            while (state == STATE_LISTENING && running) {
                int read = recorder.read(buffer, 0, buffer.length);
                if (read <= 0) { flog("Rec read=" + read); continue; }
                int amp = getAmplitude(buffer, read);
                long elapsed = System.currentTimeMillis() - t0;
                // Calibrate noise floor from first 1 second
                if (!noiseCalibrated) {
                    noiseSum += amp; noiseCount++;
                    if (elapsed > 1000) {
                        noiseFloor = Math.max(30, (int)(noiseSum / noiseCount));
                        noiseCalibrated = true;
                        flog("Noise floor=" + noiseFloor + " threshold=" + (noiseFloor * 2));
                    }
                }
                int threshold = Math.max(80, noiseFloor * 2);
                if (elapsed < 2000) flog("Listen amp=" + amp + " thr=" + threshold + " read=" + read);
                if (amp > threshold) { voiceDetected = true; silentMs = 0; buf.write(buffer, 0, read); }
                else if (voiceDetected) { silentMs += 100; buf.write(buffer, 0, read); if (silentMs >= SILENCE_TIMEOUT_MS) break; }
                if (!voiceDetected && elapsed > conversationTimeoutMs) {
                    flog(">>> No speech detected, back to idle");
                    state = STATE_IDLE; recorder.stop(); recorder.release(); recorder = null; setAudioMode(false); return;
                }
                if (elapsed > 30000) break;
            }
            recorder.stop(); recorder.release(); recorder = null;
            if (buf.size() < 3200) { flog("Skip short: " + buf.size() + "b"); setAudioMode(false); return; }
            final byte[] pcmData = buf.toByteArray();
            flog("Recorded: " + (System.currentTimeMillis() - t0) + "ms " + pcmData.length + "b");
            state = STATE_PROCESSING;
            processAudio(pcmData);
        } catch (Exception e) { flog("Listen err: " + e); safeRelease(recorder); state = STATE_IDLE; }
    }

    // ========== Processing ==========
    private void processAudio(final byte[] pcmData) {
        new AsyncTask<Void, Void, String>() {
            protected String doInBackground(Void... v) {
                try {
                    if (authToken == null) { authToken = loginAndGetToken(); if (authToken == null) return "[认证失败]"; }
                    byte[] wav = pcmToWav(pcmData, SAMPLE_RATE, 1, 16);
                    flog("STT " + wav.length + "b");
                    String stt = httpPostLocalStt(sttUrl + "/audio/transcriptions", wav, "audio.wav");
                    String text = getJsonText(stt);
                    // Strip wake word echo from end of STT result
                    if (text != null && wakeWord.length() > 1) {
                        String trimmed = text.trim();
                        if (trimmed.endsWith(wakeWord)) text = trimmed.substring(0, trimmed.length() - wakeWord.length()).trim();
                    }
                    if (text == null || text.isEmpty()) return "[\u6ca1\u542c\u6e05]";
                    flog("User: " + text);
                    lastUserText = text;
                    for (String cmd : EXIT_COMMANDS) {
                        if (text.contains(cmd)) { flog(">>> EXIT: " + cmd); return "[再见]"; }
                    }
                    String cb = "{\"input\":\"" + esc(text) + "\"";
                    if (sessionId != null) cb += ",\"session_id\":\"" + sessionId + "\"";
                    cb += "}";
                    String cr = httpPostJson(hermesUrl + "/api/chat-run/runs", cb);
                    flog("Chat: " + cr.substring(0, Math.min(200, cr.length())));
                    String ns = getJsonField(cr, "session_id");
                    if (ns != null && !ns.isEmpty()) sessionId = ns;
                    String reply = getJsonField(cr, "output");
                    if (reply == null || reply.isEmpty()) reply = getJsonField(cr, "text");
                    if (reply == null || reply.isEmpty()) return "[AI无回复]";
                    flog("AI: " + reply);
                    lastAiText = reply;
                    totalConversations++;
                    String ttsText = cleanForTts(reply);
                    String tb = "{\"text\":\"" + esc(ttsText) + "\"}";
                    byte[] audio = httpPostJsonBytes(hermesUrl + "/api/hermes/tts/synthesize", tb);
                    flog("TTS " + (audio != null ? audio.length : 0) + "b");
                    if (audio != null && audio.length > 100) { playAudioAsync(audio); return reply; }
                    return reply;
                } catch (Exception e) { flog("ERR: " + e); return "[错误]"; }
            }
            protected void onPostExecute(String r) {
                updateUI("AI: " + r);
                if (state != STATE_SPEAKING) { state = STATE_IDLE; flog(">>> Return to IDLE"); }
            }
        }.execute();
    }

    // ========== Audio Playback ==========
    private volatile boolean playStopped = false;
    private void playAudioAsync(final byte[] data) {
        playStopped = false;
        state = STATE_SPEAKING;
        updateUI("\u64ad\u653e\u4e2d...");
        flog("Play " + data.length + "b (codec)");
        new Thread() {
            public void run() {
                android.media.MediaExtractor extractor = new android.media.MediaExtractor();
                android.media.MediaCodec decoder = null;
                android.media.AudioTrack track = null;
                File f = null;
                try {
                    // Write MP3 to temp file
                    f = new File(getCacheDir(), "response.mp3");
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(data); fos.close();
                    // Set up extractor
                    extractor.setDataSource(f.getAbsolutePath());
                    extractor.selectTrack(0);
                    android.media.MediaFormat fmt = extractor.getTrackFormat(0);
                    String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
                    int sampleRate = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE);
                    int channels = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
                    flog("Codec: " + mime + " " + sampleRate + "Hz " + channels + "ch");
                    // Set up decoder
                    decoder = android.media.MediaCodec.createDecoderByType(mime);
                    decoder.configure(fmt, null, null, 0);
                    decoder.start();
                    // Set up AudioTrack
                    int chConfig = channels == 2 ? android.media.AudioFormat.CHANNEL_OUT_STEREO : android.media.AudioFormat.CHANNEL_OUT_MONO;
                    int bufSize = android.media.AudioTrack.getMinBufferSize(sampleRate, chConfig, android.media.AudioFormat.ENCODING_PCM_16BIT);
                    track = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        sampleRate, chConfig,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        bufSize, android.media.AudioTrack.MODE_STREAM);
                    track.play();
                    // Decode loop
                    android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                    boolean inputDone = false; boolean outputDone = false;
                    while (!outputDone && !playStopped) {
                        // Feed input
                        if (!inputDone) {
                            int inIdx = decoder.dequeueInputBuffer(10000);
                            if (inIdx >= 0) {
                                java.nio.ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                                int sampleSize = extractor.readSampleData(inBuf, 0);
                                if (sampleSize < 0) { decoder.queueInputBuffer(inIdx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                                else { decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.getSampleTime(), 0); extractor.advance(); }
                            }
                        }
                        // Read output
                        int outIdx = decoder.dequeueOutputBuffer(info, 10000);
                        if (outIdx >= 0) {
                            if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                            java.nio.ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                            if (outBuf != null && info.size > 0) {
                                byte[] pcm = new byte[info.size];
                                outBuf.get(pcm);
                                track.write(pcm, 0, pcm.length);
                            }
                            decoder.releaseOutputBuffer(outIdx, false);
                        }
                    }
                    // Wait for AudioTrack to finish playing
                    int written = track.getPlaybackHeadPosition();
                    if (written > 0 && sampleRate > 0) {
                        int playMs = (int)((long)written * 1000 / sampleRate);
                        flog("Play duration: " + playMs + "ms");
                        if (playMs > 0 && playMs < 60000) Thread.sleep(playMs + 200);
                    }
                    flog(">>> Play done (codec)");
                } catch (Exception e) { flog("Play err: " + e); }
                finally {
                    try { if (track != null) { track.stop(); track.release(); } } catch (Exception e) {}
                    try { if (decoder != null) { decoder.stop(); decoder.release(); } } catch (Exception e) {}
                    try { extractor.release(); } catch (Exception e) {}
                    if (f != null) f.delete();
                    onPlayComplete();
                }
            }
        }.start();
    }
    private void onPlayComplete() {
        flog(">>> Play done");
        setAudioMode(false);
        // Wait for speaker echo to dissipate before listening
        sleep(2000);
        if (continuousMode) {
            state = STATE_LISTENING;
            flog(">>> Continuous mode: back to LISTENING (echo cleared)");
        } else {
            state = STATE_IDLE;
            flog(">>> Back to IDLE, say wake word");
        }
    }
    private void playWakeAck() {
        try {
            String[] paths = {"/mnt/internal_sd/hermes_audio/wake_ack.mp3", "/sdcard/hermes_audio/wake_ack.mp3"};
            File f = null;
            for (String p : paths) { File t = new File(p); if (t.exists()) { f = t; break; } }
            if (f == null) return;
            setAudioMode(true);
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            mp.start();
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) { m.release(); }
            });
            sleep(800);
        } catch (Exception e) { flog("Wake ack err: " + e); }
    }
    /** Play wake ack without blocking — recording starts immediately */
    private void playWakeAckAsync() {
        try {
            String[] paths = {"/mnt/internal_sd/hermes_audio/wake_ack.mp3", "/sdcard/hermes_audio/wake_ack.mp3"};
            File f = null;
            for (String p : paths) { File t = new File(p); if (t.exists()) { f = t; break; } }
            if (f == null) return;
            setAudioMode(true);
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(f.getAbsolutePath());
            mp.prepare();
            mp.start();
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer m) { m.release(); }
            });
            // Don't sleep — let recording start immediately
        } catch (Exception e) { flog("Wake ack err: " + e); }
    }

    // ========== Utilities ==========
    private int getAmplitude(byte[] b, int len) {
        int amp = 0;
        for (int i = 0; i < len - 1; i += 2) { int s = (b[i] & 0xFF) | (b[i + 1] << 8); if (s < 0) s = -s; if (s > amp) amp = s; }
        return amp;
    }
    private void playBeep() { try { ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100); tg.startTone(ToneGenerator.TONE_PROP_ACK, 200); sleep(300); tg.release(); } catch (Exception e) {} }
    private void setAudioMode(boolean c) { try { AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE); if (am != null) am.setMode(c ? AudioManager.MODE_IN_COMMUNICATION : AudioManager.MODE_NORMAL); } catch (Exception e) {} }
    private void sleep(int ms) { try { Thread.sleep(ms); } catch (InterruptedException e) {} }
    private void safeRelease(AudioRecord r) { if (r != null) try { r.release(); } catch (Exception e) {} }

    private String encodeUnicode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 127) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
    private String decodeUnicode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i + 5 < s.length() && s.charAt(i) == '\\' && s.charAt(i + 1) == 'u') {
                try { int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16); sb.append((char) cp); i += 5; } catch (Exception e) { sb.append(s.charAt(i)); }
            } else sb.append(s.charAt(i));
        }
        return sb.toString();
    }
    private boolean wakeWordMatch(String text, String wakeWord) {
        if (text == null || text.isEmpty()) return false;
        String clean = text.replaceAll("[,\\\\.!?，。！？、\\\\s]+", " ").toLowerCase().trim();
        String wake = wakeWord.toLowerCase().trim();
        if (clean.contains(wake)) return true;
        String[] parts = wake.split("\\\\s+");
        for (String p : parts) { if (p.matches(".*[\\u4e00-\\u9fff].*") && p.length() >= 2 && clean.contains(p)) return true; }
        return false;
    }
    private String cleanForTts(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\*{1,2}([^*]+?)\\*{1,2}", "$1").replaceAll("_{1,2}([^_]+?)_{1,2}", "$1");
        s = s.replaceAll("(?m)^#{1,6}\\s+", "").replaceAll("\\[([^\\]]+?)\\]\\([^)]+?\\)", "$1");
        s = s.replaceAll("!\\[([^\\]]*?)\\]\\([^)]+?\\)", "$1");
        s = s.replaceAll("[\\x{1F000}-\\x{1FFFF}]", "").replaceAll("[\\x{2600}-\\x{27BF}]", "");
        s = s.replaceAll("[\\x{FE00}-\\x{FE0F}]", "").replaceAll("[\\x{200D}]", "").replaceAll("[\\x{20E3}]", "");
        s = s.replaceAll("```[\\s\\S]*?```", "").replaceAll("`([^`]+?)`", "$1");
        s = s.replaceAll("(?m)^\\s*[-=]{3,}\\s*$", "").replaceAll("(?m)^\\s*[-*•]\\s+", "");
        s = s.replaceAll("(?m)^\\s*\\d+\\.\\s+", "").replaceAll("(?m)^\\s*>\\s?", "");
        s = s.replaceAll("\\|", " ").replaceAll("[【】]", "");
        s = s.replaceAll("\\n{2,}", "。").replaceAll("\\n", "").replaceAll("\\.{2,}", "。").replaceAll("\\s{2,}", " ").trim();
        return s;
    }

    // ========== Network ==========
    private String loginAndGetToken() {
        try { String r = httpPostJson(hermesUrl + "/api/auth/login", "{\"username\":\"" + authUser + "\",\"password\":\"" + authPass + "\"}"); flog("Login: " + r.substring(0, Math.min(80, r.length()))); return getJsonField(r, "token"); }
        catch (Exception e) { flog("Login err: " + e); return null; }
    }
    private String httpPostJson(String u, String body) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "application/json");
        if (authToken != null) c.setRequestProperty("Authorization", "Bearer " + authToken);
        OutputStream o = c.getOutputStream(); o.write(body.getBytes("UTF-8")); o.flush(); o.close();
        int code = c.getResponseCode(); InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        if (is == null) return "{\"error\":\"" + code + "\"}";
        ByteArrayOutputStream r = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int l;
        while ((l = is.read(b)) != -1) r.write(b, 0, l); is.close(); return r.toString("UTF-8");
    }
    private byte[] httpPostJsonBytes(String u, String body) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(15000); c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "application/json");
        if (authToken != null) c.setRequestProperty("Authorization", "Bearer " + authToken);
        OutputStream o = c.getOutputStream(); o.write(body.getBytes("UTF-8")); o.flush(); o.close();
        InputStream is = c.getInputStream(); ByteArrayOutputStream r = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int l;
        while ((l = is.read(b)) != -1) r.write(b, 0, l); is.close(); return r.toByteArray();
    }
    private String httpPostLocalStt(String u, byte[] data, String fn) throws Exception {
        String bd = "----HV" + UUID.randomUUID().toString().replace("-", "");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL(u).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setConnectTimeout(10000); c.setReadTimeout(60000);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bd);
        flog("STT connecting to " + u);
        OutputStream o = c.getOutputStream(); DataOutputStream d = new DataOutputStream(o);
        d.writeBytes("--" + bd + "\r\n"); d.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"" + fn + "\"\r\n");
        d.writeBytes("Content-Type: audio/wav\r\n\r\n"); d.write(data);
        d.writeBytes("\r\n--" + bd + "\r\n"); d.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1");
        d.writeBytes("\r\n--" + bd + "\r\n"); d.writeBytes("Content-Disposition: form-data; name=\"language\"\r\n\r\nzh");
        d.writeBytes("\r\n--" + bd + "--\r\n"); d.flush(); d.close();
        flog("STT request sent, waiting for response...");
        int code = c.getResponseCode();
        flog("STT response code: " + code);
        InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
        if (is == null) return "{\"error\":\"" + code + "\"}";
        ByteArrayOutputStream r = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int l;
        while ((l = is.read(b)) != -1) r.write(b, 0, l); is.close();
        String resp = r.toString("UTF-8");
        flog("STT response: " + resp.substring(0, Math.min(200, resp.length())));
        return resp;
    }
    private String getJsonField(String j, String f) {
        int i = j.indexOf("\"" + f + "\""); if (i < 0) return null;
        i = j.indexOf(":", i) + 1; while (i < j.length() && j.charAt(i) == ' ') i++;
        if (i >= j.length()) return null;
        if (j.charAt(i) == '"') { i++; int s = i;
            while (i < j.length()) { if (j.charAt(i) == '\\') { i += 2; continue; } if (j.charAt(i) == '"') break; i++; }
            String val = j.substring(s, i).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < val.length(); k++) {
                if (k + 5 < val.length() && val.charAt(k) == '\\' && val.charAt(k + 1) == 'u') {
                    try { int cp = Integer.parseInt(val.substring(k + 2, k + 6), 16); sb.append((char) cp); k += 5; } catch (Exception e) { sb.append(val.charAt(k)); }
                } else sb.append(val.charAt(k));
            } return sb.toString(); }
        int s = i; while (i < j.length() && j.charAt(i) != ',' && j.charAt(i) != '}' && j.charAt(i) != ']') i++;
        return j.substring(s, i).trim();
    }
    private String getJsonText(String stt) { String t = getJsonField(stt, "text"); if (t == null || t.isEmpty()) t = getJsonField(stt, "transcription"); return t; }
    private String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r"); }
    private String escJson(String s) { if (s == null) return ""; return s.replace("\\", "\\\\").replace("\"", "\\\""); }
    private Map<String, String> parseFormData(String data) {
        Map<String, String> map = new HashMap<String, String>();
        if (data == null || data.isEmpty()) return map;
        for (String pair : data.split("&")) { String[] kv = pair.split("=", 2);
            if (kv.length == 2) try { map.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8")); } catch (Exception e) {} }
        return map;
    }
    private byte[] pcmToWav(byte[] pcm, int sr, int ch, int bits) {
        int br = sr * ch * bits / 8, ba = ch * bits / 8, ds = pcm.length;
        byte[] h = new byte[44]; h[0]='R';h[1]='I';h[2]='F';h[3]='F'; putI(h,4,36+ds);
        h[8]='W';h[9]='A';h[10]='V';h[11]='E'; h[12]='f';h[13]='m';h[14]='t';h[15]=' ';
        putI(h,16,16); putS(h,20,(short)1); putS(h,22,(short)ch); putI(h,24,sr); putI(h,28,br); putS(h,32,(short)ba); putS(h,34,(short)bits);
        h[36]='d';h[37]='a';h[38]='t';h[39]='a'; putI(h,40,ds);
        byte[] w = new byte[44+ds]; System.arraycopy(h,0,w,0,44); System.arraycopy(pcm,0,w,44,ds); return w;
    }
    private void putI(byte[] d, int o, int v) { d[o]=(byte)v;d[o+1]=(byte)(v>>8);d[o+2]=(byte)(v>>16);d[o+3]=(byte)(v>>24); }
    private void putS(byte[] d, int o, short v) { d[o]=(byte)v;d[o+1]=(byte)(v>>8); }
    private void flog(final String msg) {
        Log.d(TAG, msg);
        try { FileWriter fw = new FileWriter("/sdcard/hermes_voice.log", true); fw.write(System.currentTimeMillis() + " " + msg + "\r\n"); fw.close(); } catch (Exception e) {}
        runOnUiThread(new Runnable() { public void run() { if (statusText != null) statusText.setText(msg); } });
    }
    private void updateUI(final String msg) { runOnUiThread(new Runnable() { public void run() { if (responseText != null) responseText.setText(msg); } }); }
    @Override protected void onDestroy() { running = false; if (webServer != null) webServer.stop(); super.onDestroy(); }
}
