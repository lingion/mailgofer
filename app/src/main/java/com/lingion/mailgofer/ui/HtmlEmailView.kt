package com.lingion.mailgofer.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * HTML 邮件渲染。WebView 只加载邮件正文(不加载整页),禁 JS 文件访问,
 * 链接交给系统浏览器。CSS 走内联 dark 友好处理: 强制白底(邮件自带样式常假定白底)。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlEmailView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkImage = false   // 邮件里的图片要显示(用户明确要求)
                settings.loadsImagesAutomatically = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?, request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        // 外链丢系统浏览器, 不在邮件 WebView 里跳
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                        return true
                    }
                }
            }
        },
        update = { it.loadDataWithBaseURL(null, wrapHtml(html), "text/html", "utf-8", null) }
    )
}

/** 邮件正文常无 viewport/宽度约束,补一层兜底样式防横向溢出 */
private fun wrapHtml(body: String): String = """
    <html><head><meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
      body { margin:0; padding:0; font-family:-apple-system,'Segoe UI',Roboto,sans-serif;
             font-size:15px; line-height:1.5; color:#1b1b1b; background:#fff;
             word-wrap:break-word; overflow-x:hidden; }
      img { max-width:100% !important; height:auto !important; }
      table { max-width:100% !important; }
      pre { white-space:pre-wrap; }
    </style></head><body>$body</body></html>
""".trimIndent()
