package com.thirakkatha.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Thirakkatha for Android.
 * The whole app is the web page in assets/www. This activity shows it and gives it
 * the phone features a web page can't reach on its own: voice typing, saving and
 * opening files, printing, and reading aloud.
 */
public class MainActivity extends Activity {

    private static final String HOST = "app.thirakkatha.local";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_MIC = 1;
    private static final int REQ_SAVE = 2;
    private static final int REQ_OPEN = 3;

    private WebView web;

    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private int quietRounds = 0;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech = null;

    private String pendingSaveText = null;
    private String pendingSaveName = "";
    private ValueCallback<Uri[]> fileCallback = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.addJavascriptInterface(new Bridge(), "ThkNative");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                try {
                    InputStream in = getAssets().open("www" + path);
                    String mime = mimeFor(path);
                    String enc = mime.startsWith("image/") ? null : "UTF-8";
                    return new WebResourceResponse(mime, enc, in);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found",
                            new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(START_URL);
        }
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json";
        return "application/octet-stream";
    }

    /** Run a line of JavaScript in the page. */
    private void js(final String code) {
        web.post(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(code, null);
            }
        });
    }

    private void voiceEvent(String type, String text) {
        js("window.__thkVoice&&window.__thkVoice(" + JSONObject.quote(type) + "," + JSONObject.quote(text == null ? "" : text) + ")");
    }

    private void ttsEvent(String type) {
        js("window.__thkTts&&window.__thkTts(" + JSONObject.quote(type) + ")");
    }

    // ------------------------------------------------------------------ voice typing

    private void beginVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceEvent("error", "unavailable");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(voiceListener);
        }
        listening = true;
        quietRounds = 0;
        listenOnce();
    }

    private void listenOnce() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ml-IN");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ml-IN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            listening = false;
            voiceEvent("error", "other");
        }
    }

    private void listenAgainSoon() {
        web.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (listening) listenOnce();
            }
        }, 250);
    }

    private void endVoice() {
        listening = false;
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    private static String firstResult(Bundle b) {
        if (b == null) return "";
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list == null || list.isEmpty() || list.get(0) == null) ? "" : list.get(0);
    }

    private final RecognitionListener voiceListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (listening) voiceEvent("partial", firstResult(partialResults));
        }

        @Override
        public void onResults(Bundle results) {
            String text = firstResult(results);
            if (!text.isEmpty()) {
                quietRounds = 0;
                voiceEvent("final", text);
            }
            if (listening) listenAgainSoon();
        }

        @Override
        public void onError(int error) {
            if (!listening) return;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                case SpeechRecognizer.ERROR_CLIENT:
                    // silence: keep listening, but give up after a long quiet spell
                    if (++quietRounds > 12) {
                        listening = false;
                        voiceEvent("error", "other");
                    } else {
                        listenAgainSoon();
                    }
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    try {
                        recognizer.cancel();
                    } catch (Exception ignored) {
                    }
                    listenAgainSoon();
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    listening = false;
                    voiceEvent("error", "permission");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    listening = false;
                    voiceEvent("error", "network");
                    break;
                case 12:    // language not supported
                case 13:    // language unavailable
                    listening = false;
                    voiceEvent("error", "language");
                    break;
                default:
                    listening = false;
                    voiceEvent("error", "other");
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            beginVoice();
        } else {
            voiceEvent("error", "permission");
        }
    }

    // ------------------------------------------------------------------ read aloud

    private void speakNow(String text) {
        if (tts == null || !ttsReady) {
            ttsEvent("error");
            return;
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "thk");
    }

    private void speak(final String text) {
        if (tts != null) {
            if (ttsReady) speakNow(text);
            else pendingSpeech = text;
            return;
        }
        pendingSpeech = text;
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) {
                    ttsEvent("error");
                    return;
                }
                int r = tts.setLanguage(new Locale("ml", "IN"));
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsEvent("language");
                    return;
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        ttsEvent("done");
                    }

                    @Override
                    public void onError(String utteranceId) {
                        ttsEvent("error");
                    }
                });
                ttsReady = true;
                if (pendingSpeech != null) {
                    String t = pendingSpeech;
                    pendingSpeech = null;
                    speakNow(t);
                }
            }
        });
    }

    // ------------------------------------------------------------------ files

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;

        if (requestCode == REQ_OPEN) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(uri == null ? null : new Uri[]{uri});
                fileCallback = null;
            }
        } else if (requestCode == REQ_SAVE) {
            boolean ok = false;
            if (uri != null && pendingSaveText != null) {
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        out.write(pendingSaveText.getBytes(StandardCharsets.UTF_8));
                        out.close();
                        ok = true;
                    }
                } catch (Exception ignored) {
                }
            }
            pendingSaveText = null;
            js("window.__thkSaved&&window.__thkSaved(" + ok + "," + JSONObject.quote(pendingSaveName) + "," + (uri == null) + ")");
        }
    }

    // ------------------------------------------------------------------ bridge the page calls

    private class Bridge {

        @JavascriptInterface
        public void startVoice() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    beginVoice();
                }
            });
        }

        @JavascriptInterface
        public void stopVoice() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    endVoice();
                }
            });
        }

        @JavascriptInterface
        public void speak(final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.speak(text == null ? "" : text);
                }
            });
        }

        @JavascriptInterface
        public void stopSpeak() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingSpeech = null;
                    if (tts != null) tts.stop();
                }
            });
        }

        /** Opens Android's save screen. Google Drive appears there as a place to save. */
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    pendingSaveText = text == null ? "" : text;
                    pendingSaveName = name == null ? "script" : name;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    i.putExtra(Intent.EXTRA_TITLE, pendingSaveName);
                    try {
                        startActivityForResult(i, REQ_SAVE);
                    } catch (ActivityNotFoundException e) {
                        pendingSaveText = null;
                        js("window.__thkSaved&&window.__thkSaved(false," + JSONObject.quote(pendingSaveName) + ",false)");
                    }
                }
            });
        }

        @JavascriptInterface
        public void print(final String title) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    String job = (title == null || title.trim().isEmpty()) ? "Thirakkatha script" : title.trim();
                    PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                    if (pm != null) {
                        pm.print(job, web.createPrintDocumentAdapter(job), new PrintAttributes.Builder().build());
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__thkBack&&window.__thkBack())?1:0", new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (!"1".equals(value)) moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        js("window.__thkFlush&&window.__thkFlush()");
        if (listening) {
            endVoice();
            voiceEvent("error", "other");
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
