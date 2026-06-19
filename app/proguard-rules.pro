# Keep JavaScript interface members exposed to WebView.
-keepclassmembers class com.myapp.drivebrowser.** {
    @android.webkit.JavascriptInterface <methods>;
}
-dontobfuscate
