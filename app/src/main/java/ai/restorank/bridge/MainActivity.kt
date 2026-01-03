package ai.restorank.bridge

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.webkit.*
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var txtStatus: TextView

    companion object {
        private const val WEB_URL = "https://restorank.replit.app/#/dashboard/home"
        private const val TAG = "RestoRankBridge"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupWebView()
        setupClickListeners()
        updateStatus()
        
        // Start the auto-print service
        startService(Intent(this, PrintService::class.java))

        webView.loadUrl(WEB_URL)
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        txtStatus = findViewById(R.id.txtStatus)

        swipeRefresh.setColorSchemeResources(
            android.R.color.holo_green_light,
            android.R.color.holo_blue_bright,
            android.R.color.holo_orange_light
        )
        swipeRefresh.setOnRefreshListener { webView.reload() }
    }

    private fun setupClickListeners() {
        findViewById<ImageButton>(R.id.btnPrinterSettings).setOnClickListener {
            startActivity(Intent(this, PrinterSettingsActivity::class.java))
        }

        findViewById<ImageButton>(R.id.btnRefresh).setOnClickListener {
            webView.reload()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    swipeRefresh.isRefreshing = false
                    Toast.makeText(this@MainActivity, "Connection error. Pull to refresh.", Toast.LENGTH_LONG).show()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return if (url.startsWith("http://") || url.startsWith("https://")) {
                    if (url.contains("restorank") || url.contains("replit")) {
                        false
                    } else {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    }
                } else {
                    true
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
            }
        }
    }

    private fun updateStatus() {
        val printerManager = PrinterManager(this)
        val printers = printerManager.getPrinters()
        val serviceRunning = PrintService.isRunning
        
        txtStatus.visibility = View.VISIBLE
        if (printers.isEmpty()) {
            txtStatus.text = "No printers configured - tap gear icon to add"
            txtStatus.setBackgroundColor(0xFFFEE2E2.toInt())
        } else {
            val status = if (serviceRunning) "Auto-print: ON" else "Auto-print: OFF"
            txtStatus.text = "$status | ${printers.size} printer(s) configured"
            txtStatus.setBackgroundColor(0xFFD1FAE5.toInt())
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
