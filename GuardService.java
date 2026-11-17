package id.juban.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strict mode. While protection is locked (on, and the 24-hour wait has not passed), this
 * closes any system screen that could switch JuBan off: its App info page, the device admin
 * page, the uninstall dialog, its Accessibility switch and its VPN settings. See GuardRules
 * for exactly which screens. When the lock opens, it does nothing at all.
 *
 * It reads only system screens and keeps nothing it reads.
 */
public class GuardService extends AccessibilityService {

    private static final int MAX_NODES = 600;
    private static final int MAX_PAGE_NODES = 1500;
    private static final long SCREEN_SCAN_MS = 1200;
    private long lastScreenScan = 0;
    private long lastBlock = 0;
    private static final long BROWSER_LIST_MS = 5 * 60 * 1000;
    private Set<String> installedBrowsers = new HashSet<>();
    private long browsersAt = -BROWSER_LIST_MS;

    /**
     * Any app that can open a web link for a made-up address counts as a browser (Quetta, Kiwi,
     * Via, ...), not only the ones in the built-in list. Apps that only claim certain sites
     * (YouTube, Instagram, shops) do not match a made-up address, so they are not read.
     * The list is read from Android and refreshed every few minutes.
     */
    private boolean isBrowser(String pkg) {
        if (GuardRules.browser(pkg)) return true;
        long now = SystemClock.elapsedRealtime();
        if (now - browsersAt > BROWSER_LIST_MS) {
            browsersAt = now;
            Set<String> found = new HashSet<>();
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://juban-probe-7f3a91.example/"));
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                List<ResolveInfo> list = getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL);
                for (ResolveInfo r : list) {
                    if (r.activityInfo != null) found.add(r.activityInfo.packageName);
                }
            } catch (RuntimeException ignored) { }
            found.remove(getPackageName());
            installedBrowsers = found;
        }
        return GuardRules.notShell(pkg) && installedBrowsers.contains(pkg);
    }

    /** True if the user has switched strict mode on in Android's Accessibility settings. */
    public static boolean isOn(Context c) {
        String list;
        try {
            list = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (RuntimeException e) {
            return false;
        }
        if (list == null) return false;
        String me = new ComponentName(c, GuardService.class).flattenToString();
        String shortMe = new ComponentName(c, GuardService.class).flattenToShortString();
        for (String s : list.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(shortMe)) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (isBrowser(pkg)) {
            screenCheck();
            return;
        }
        boolean home = GuardRules.launcher(pkg);
        if (!home && !GuardRules.watched(pkg, getPackageName())) return;
        // On the home screen only new windows (a pop-up) matter, never scrolling or typing.
        if (home && event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!Prefs.locked(this)) return;
        if (SystemClock.elapsedRealtime() - lastBlock < 800) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        StringBuilder text = new StringBuilder();
        collect(root, text, new int[]{0});
        boolean close = home ? GuardRules.launcherDialog(text.toString()) : GuardRules.dangerous(text.toString());
        if (!close) return;

        // Just step back out of that one screen or pop-up; never jump around the phone.
        lastBlock = SystemClock.elapsedRealtime();
        performGlobalAction(GLOBAL_ACTION_BACK);
        try {
            Toast.makeText(this, R.string.strict_blocked, Toast.LENGTH_LONG).show();
        } catch (RuntimeException ignored) { }
    }

    /**
     * Screen check: reads the page shown in a browser (on the phone only; nothing is kept)
     * and, if it is a Ju page, goes back and remembers the site so it is blocked from then on.
     */
    private void screenCheck() {
        if (!Prefs.enabled(this) || !Prefs.screenCheck(this)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastScreenScan < SCREEN_SCAN_MS || now - lastBlock < 1500) return;
        lastScreenScan = now;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        StringBuilder text = new StringBuilder();
        String[] host = {null};
        collectPage(root, text, host, new int[]{0});
        ContentScorer.Result r = ContentScorer.score(text.toString());
        if (!r.ju) return;
        if (host[0] != null) {
            DomainMatcher m = Prefs.buildMatcher(this);
            if (m.allowedByUser(host[0])) return;               // "Always allow" wins
            if (PageCheckPolicy.eligible(host[0], 1)) Prefs.pageChecker(this).markJu(host[0]);
        }
        lastBlock = now;
        performGlobalAction(GLOBAL_ACTION_BACK);
        BlockVpnService.noteScreenBlock(host[0] != null ? host[0] : "?");
        try {
            Toast.makeText(this, R.string.screen_blocked, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) { }
    }

    private static void collectPage(AccessibilityNodeInfo node, StringBuilder out, String[] host, int[] count) {
        if (node == null || count[0]++ > MAX_PAGE_NODES) return;
        CharSequence t = node.getText();
        if (t != null) {
            out.append(t).append('\n');
            String id = node.getViewIdResourceName();
            if (host[0] == null && id != null && (id.contains("url") || id.contains("location_bar") || id.contains("address"))) {
                host[0] = GuardRules.hostFromAddressBar(t.toString());
            }
        }
        CharSequence d = node.getContentDescription();
        if (d != null) out.append(d).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            collectPage(node.getChild(i), out, host, count);
        }
    }

    private static void collect(AccessibilityNodeInfo node, StringBuilder out, int[] count) {
        if (node == null || count[0]++ > MAX_NODES) return;
        CharSequence t = node.getText();
        if (t != null) out.append(t).append('\n');
        CharSequence d = node.getContentDescription();
        if (d != null) out.append(d).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), out, count);
        }
    }

    @Override
    public void onInterrupt() { }
}
