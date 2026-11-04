# Apps to check after changing the system WebView

Done on the tablet after WebView Beta became the provider (`tools/framework/allow_webview_beta.py`). Nothing here was tested yet when this list was written.

**Mostly web pages inside the app (best test)**
1. Home Assistant app: the whole app is a WebView.
2. LG ThinQ: the Welcome and sign in screens are web content.
3. Google Home: setup and account pages; open the ecobee and WashTower cards.
4. FoxMMM and the Magisk app: the Modules screens show web content.

**Sign in and settings screens**
5. Settings, Passwords & accounts, Add account (Google).
6. Play Store: an app page, the Account page, a payment screen.
7. 1.1.1.1 and Cloudflare apps: any web login step.

**Google apps**
8. Google app: Discover feed and "Customize your space".
9. Maps and YouTube: open a link from inside the app.
10. Photos and Messages: open a shared link.

**Pixel and system**
11. Wallpaper & style: the theme packs page.
12. At a Glance: tap the weather text.
13. Pillion and DiPlay: Settings and About pages.

**What to look for:** every page loads fully (no blank white or black screen), no "app has stopped" dialog, scrolling and tapping work.

**Go back:** Developer options, WebView implementation, Android WebView; or `cmd webviewupdate set-webview-implementation com.android.webview`.
