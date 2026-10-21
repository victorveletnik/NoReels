package com.example.igminimal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

class MainActivity : AppCompatActivity() {

    companion object {
        private const val HOME = "https://www.instagram.com/"
        private const val INBOX = "https://www.instagram.com/direct/inbox/"

        // Domeny, które mogą się ładować w aplikacji. Reszta idzie do zewnętrznej przeglądarki.
        private val ALLOWED_HOSTS =
            listOf("instagram.com", "facebook.com", "cdninstagram.com", "fbcdn.net")

        private const val OFFLINE_HTML =
            "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                "<body style='background:#111;color:#eee;font-family:sans-serif;text-align:center;padding-top:30vh'>" +
                "<h2>Brak połączenia</h2><p>Sprawdź internet i spróbuj ponownie.</p>" +
                "<button style='font-size:18px;padding:12px 28px;border-radius:10px;border:0' " +
                "onclick='AndroidBridge.retry()'>Spróbuj ponownie</button></body></html>"

        /*
         * EDYTUJ TU, gdy Instagram coś zmieni.
         *  - CSS: co ukryć (klasa ig-home jest dodawana tylko na stronie głównej "/")
         *  - OK:  dozwolone ścieżki (reszta jest blokowana)
         */
        private val MINIMAL_JS = """
            (function() {
              if (location.protocol.indexOf('http') !== 0) return;
              if (window.__igMin) return;
              window.__igMin = true;

              var CSS = [
                'html.ig-home article { display:none !important; }',
                'nav, [role="tablist"] { display:none !important; }',
                'a[href^="/reels"], a[href^="/explore"] { display:none !important; }'
              ].join('\n');

              var OK = ['/direct', '/stories', '/accounts', '/challenge', '/web'];

              function allowed(p) {
                if (p === '/') return true;
                for (var i = 0; i < OK.length; i++) { if (p.indexOf(OK[i]) === 0) return true; }
                return false;
              }

              var lastPath = null;
              var lastBlock = 0;

              function apply() {
                var p = location.pathname;

                // Przed zalogowaniem nic nie ukrywamy ani nie blokujemy (logowanie, 2FA, zgody)
                var loggedIn = window.AndroidBridge && AndroidBridge.loggedIn();
                if (!loggedIn) {
                  var old = document.getElementById('ig-min');
                  if (old) old.remove();
                  document.documentElement.classList.remove('ig-home');
                  return;
                }

                if (!document.getElementById('ig-min')) {
                  var s = document.createElement('style');
                  s.id = 'ig-min';
                  s.innerHTML = CSS;
                  (document.head || document.documentElement).appendChild(s);
                }
                document.documentElement.classList.toggle('ig-home', p === '/');

                if (!allowed(p)) {
                  if (Date.now() - lastBlock < 1000) return;
                  lastBlock = Date.now();
                  if (window.AndroidBridge) AndroidBridge.blocked();
                  if (history.length > 1) { history.back(); } else { location.replace('/'); }
                  return;
                }

                if (p !== lastPath) {
                  lastPath = p;
                  if (window.AndroidBridge) AndroidBridge.onPath(p);
                }
              }

              apply();
              new MutationObserver(apply).observe(document.documentElement, {childList: true, subtree: true});
              setInterval(apply, 800);
            })();
        """.trimIndent()
    }

    private lateinit var web: WebView
    private lateinit var bar: LinearLayout
    private lateinit var btnStories: Button
    private lateinit var btnMessages: Button

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null

    // ---------- wybór plików (zdjęcia/filmy w DM) ----------
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            )
            fileCallback = null
        }

    // ---------- uprawnienia kamery/mikrofonu na żądanie ----------
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingPermission?.let { grantAllowed(it) }
            pendingPermission = null
        }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun grantAllowed(req: PermissionRequest) {
        val ok = req.resources.filter { res ->
            when (res) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> granted(Manifest.permission.CAMERA)
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> granted(Manifest.permission.RECORD_AUDIO)
                else -> false
            }
        }.toTypedArray()
        if (ok.isEmpty()) req.deny() else req.grant(ok)
    }

    // ---------- most JS <-> aplikacja ----------
    inner class Bridge {
        // Czy jest aktywna sesja? Ciasteczko sessionid ustawia się dopiero po pełnym zalogowaniu.
        @JavascriptInterface
        fun loggedIn(): Boolean {
            val cookies = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: return false
            return cookies.contains("sessionid=")
        }

        @JavascriptInterface
        fun blocked() {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Ta sekcja jest wyłączona", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun onPath(path: String) {
            runOnUiThread {
                // W relacjach chowamy dolny pasek, żeby były na pełnym ekranie
                bar.visibility = if (path.startsWith("/stories")) View.GONE else View.VISIBLE
                btnStories.alpha = if (path.startsWith("/direct")) 0.45f else 1f
                btnMessages.alpha = if (path.startsWith("/direct")) 1f else 0.45f
            }
        }

        @JavascriptInterface
        fun retry() {
            runOnUiThread { web.loadUrl(HOME) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---------- UI: WebView + dolny pasek ----------
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#111111"))
        }
        fun navButton(label: String, url: String) = Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { web.loadUrl(url) }
        }
        btnStories = navButton("Relacje", HOME)
        btnMessages = navButton("Wiadomości", INBOX)
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        bar.addView(btnStories, lp)
        bar.addView(btnMessages, lp)
        root.addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)

        // ---------- WebView ----------
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            // Udajemy zwykłego Chrome (bez "; wv"), żeby Instagram nie pokazywał ograniczeń
            userAgentString = userAgentString.replace("; wv", "").replace(" Version/4.0", "")
        }
        // Ciemny motyw podąża za ustawieniem systemu
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val scheme = req.url.scheme ?: return true
                // Głębokie linki typu instagram://, intent:// - ignorujemy
                if (scheme != "http" && scheme != "https") return true

                val host = req.url.host ?: return true
                val internal = ALLOWED_HOSTS.any { d -> host == d || host.endsWith(".$d") }
                if (!internal) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, req.url))
                    } catch (e: Exception) {
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url.startsWith("https://")) view.evaluateJavascript(MINIMAL_JS, null)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    view.loadDataWithBaseURL(null, OFFLINE_HTML, "text/html", "utf-8", null)
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                v: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                return try {
                    filePicker.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val host = request.origin.host ?: ""
                    if (host != "instagram.com" && !host.endsWith(".instagram.com")) {
                        request.deny()
                        return@runOnUiThread
                    }
                    val need = mutableListOf<String>()
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in request.resources &&
                        !granted(Manifest.permission.CAMERA)
                    ) need += Manifest.permission.CAMERA
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources &&
                        !granted(Manifest.permission.RECORD_AUDIO)
                    ) need += Manifest.permission.RECORD_AUDIO

                    if (need.isEmpty()) {
                        grantAllowed(request)
                    } else {
                        pendingPermission?.deny()
                        pendingPermission = request
                        permLauncher.launch(need.toTypedArray())
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(HOME)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        // Zapis ciasteczek = sesja przeżywa zamknięcie aplikacji
        CookieManager.getInstance().flush()
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
