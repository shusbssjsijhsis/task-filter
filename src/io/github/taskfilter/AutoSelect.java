package io.github.taskfilter;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decides which apps to hide, using only what this device can tell about each app; nothing is sent
 * anywhere. Used by the settings screen ("auto-select") and by the hook for apps installed later.
 *
 * Order (first match wins):
 * 1. an Xposed module (metadata or entry files) - checked first, as modules may borrow a domestic package name;
 * 2. the explicit lists: root tools and foreign apps are chosen, domestic platforms never are;
 * 3. a bundled proxy core or routing database (geoip/geosite, clash, xray ...) or an app-cloning / virtual-machine
 *    framework is chosen even when the app carries domestic payment SDKs, as subscription "accelerators" often do;
 * 4. a Chinese push, login, payment or sharing SDK, or an install from a domestic app store, marks a domestic app,
 *    never chosen - even with a VPN service (carriers and game helpers use one for "network acceleration");
 * 5. a VPN service or the Shizuku permission is chosen; FCM or a Google Play / F-Droid install is chosen as inferred;
 * 6. anything else is "unknown" (a weaker domestic SDK, such as crash reporting or ads, is shown as a hint).
 * System apps, the launcher, WebView and Play services are never chosen.
 */
final class AutoSelect {
    private AutoSelect() {}

    enum Kind {
        VPN("代理 / VPN"),
        XPOSED("Xposed 模块"),
        ROOT("root 及相关工具"),
        FOREIGN("境外应用"),
        FOREIGN_INFERRED("境外应用（推断）"),
        UNKNOWN("未识别");

        final String title;

        Kind(String title) {
            this.title = title;
        }
    }

    enum Outcome { PICK, UNKNOWN, DOMESTIC, SYSTEM, PROTECTED, FAILED }

    static final class Verdict {
        final Outcome outcome;
        final Kind kind;
        /** Why, in a few words, for the settings screen. */
        final String reason;
        /** For DOMESTIC: decided by a list, an SDK or the store, all of which are reliable. */
        final boolean strong;

        Verdict(Outcome outcome, Kind kind, String reason, boolean strong) {
            this.outcome = outcome;
            this.kind = kind;
            this.reason = reason;
            this.strong = strong;
        }
    }

    static final class Pick {
        final String pkg;
        final String label;
        final Kind kind;
        final String reason;
        /** Pre-selected in the settings screen's dialog. */
        final boolean preChecked;

        Pick(String pkg, String label, Kind kind, String reason, boolean preChecked) {
            this.pkg = pkg;
            this.label = label;
            this.kind = kind;
            this.reason = reason;
            this.preChecked = preChecked;
        }
    }

    static final class Result {
        /** Not hidden yet, and recognised: suggested (pre-selected). */
        final List<Pick> picks = new ArrayList<>();
        /** Not hidden yet, and no signal either way: offered, not pre-selected. */
        final List<Pick> unknown = new ArrayList<>();
        /** Hidden, but worth a second look: essential components, auto-ticked domestic apps, Mi Push users. */
        final List<Pick> review = new ArrayList<>();
        int system;
        int domestic;
        int protectedApps;
        int failed;
        /** Turned down by the user before: not offered again. */
        int declined;
    }

    // ------------------------------------------------------------------------------------------
    // Rules. A list entry matches the package itself and everything below it ("com.google" matches
    // "com.google.android.youtube"). These are generic public lists, not anyone's phone.

    private static final String[] ROOT = {
            // root managers
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu",
            "com.rifsxd.ksunext", "com.sukisu.ultra", "me.bmax.apatch",
            // Xposed managers, patchers and frameworks without root, Shizuku
            "org.lsposed.manager", "org.lsposed.lspatch", "io.va.exposed", "me.weishu.exp", "moe.shizuku.privileged.api",
            // virtual machines and app cloners
            "com.vmos", "com.vphonegaga", "com.lbe.parallel", "com.excelliance",
            // terminals, file, package and backup tools
            "com.termux", "bin.mt.plus", "bin.mt.termex", "io.github.muntashirakon.AppManager", "com.rosan.installer.x",
            "com.aistra.hail", "com.catchingnow.icebox", "org.swiftapps.swiftbackup", "com.machiav3lli.backup",
            "com.keramidas.TitaniumBackup", "com.dp.logcatapp",
            // kernel and performance tools
            "com.github.capntrips.kernelflasher", "xzr.konabess", "com.franco.kernel", "flar2.exkernelmanager",
            "com.smartpack.kernelmanager", "com.omarea.vtools",
            // root / integrity / environment checkers
            "io.github.vvb2060.keyattestation", "io.github.vvb2060.mahoshojo", "icu.nullptr.nativetest",
            "icu.nullptr.applistdetector", "com.tsng.applistdetector", "com.reveny.nativecheck", "com.zhenxi.hunter",
            "com.byxiaorun.detector", "rikka.safetynetchecker", "gr.nikolasspyr.integritycheck",
            "com.henrikherzig.playintegritychecker", "krypton.tbsafetychecker",
    };

    private static final String[] FOREIGN = {
            // Google (system Google components are skipped before this list is consulted)
            "com.google", "com.android.chrome", "com.chrome",
            // messaging and social
            "org.telegram", "org.thunderdog.challegram", "tw.nekomimi.nekogram", "com.exteragram.messenger",
            "uz.unnarsx.cherrygram", "xyz.nextalone.nagram", "org.thoughtcrime.securesms", "com.whatsapp",
            "com.facebook", "com.instagram", "com.twitter", "com.reddit", "com.discord", "com.snapchat",
            "com.pinterest", "com.tumblr", "com.linkedin", "jp.naver.line", "com.viber", "com.skype", "us.zoom",
            "org.joinmastodon", "xyz.blueskyweb", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.quora",
            "jp.pxv",
            // browsers
            "org.mozilla", "com.brave", "com.opera", "com.vivaldi", "com.kiwibrowser", "com.duckduckgo",
            "org.torproject",
            // Microsoft, Proton, password managers, authenticators
            "com.microsoft", "com.azure.authenticator", "ch.protonmail", "ch.protonvpn", "me.proton",
            "com.x8bit.bitwarden", "com.agilebits", "com.lastpass", "com.authy", "com.beemdevelopment.aegis",
            // AI assistants
            "com.openai", "com.anthropic", "ai.x.grok", "ai.perplexity",
            // media, stores, payments, productivity, utilities
            "com.spotify", "com.netflix", "com.amazon", "com.disney", "tv.twitch", "com.valvesoftware",
            "com.paypal", "com.revolut", "com.transferwise", "com.dropbox", "md.obsidian", "notion.id", "com.slack",
            "org.wikipedia", "org.fdroid", "com.aurora.store", "com.looker.droidify", "org.localsend", "com.mxtech",
            "com.anydesk", "idm.internet.download.manager", "com.pikcloud",
            // crypto exchanges and wallets
            "com.binance", "com.okinc", "com.bybit", "com.bitget", "com.bgwapp", "com.bitkeep", "com.coinbase",
            "com.kraken", "com.gateio", "pro.huobi", "com.kubi.kucoin", "com.mexcpro", "io.metamask",
            "com.wallet.crypto.trustapp", "org.toshi", "im.token.app", "vip.mytokenpocket", "co.mona.android",
            "piuk.blockchain.android",
            // news
            "com.nytimes", "bbc.mobile.news", "com.guardian", "com.thomsonreuters", "wsj.reader_sp", "com.ft.news",
            "com.economist", "com.bloomberg",
            // proxy and VPN clients
            "com.github.shadowsocks", "com.v2ray", "com.github.kr328.clash", "com.github.metacubex",
            "io.nekohasekai", "moe.nb4a", "com.follow.clash", "app.hiddify", "com.hiddify", "io.geph",
            "org.outline", "com.psiphon3", "org.getlantern", "com.nthlink", "com.cloudflare.onedotonedotonedotone",
            "net.mullvad", "com.expressvpn", "com.nordvpn", "com.surfshark", "com.windscribe", "de.blinkt.openvpn",
            "net.openvpn", "com.wireguard",
    };

    /**
     * Domestic platforms: never chosen, whatever else they declare. Meant for the large apps whose
     * notifications or accounts matter, not for every app made in China.
     */
    private static final String[] DOMESTIC = {
            // large platforms
            "com.tencent", "com.qq", "com.alibaba", "com.taobao", "com.tmall", "com.alipay",
            "com.eg.android.AlipayGphone", "com.ss.android", "com.bytedance", "com.larus", "com.lemon.lv",
            "com.dragon.read", "com.xingin", "com.smile.gifmaker", "com.kuaishou", "com.kwai", "com.baidu",
            "com.netease", "com.sankuai", "com.meituan", "com.dianping", "com.jingdong", "com.jd", "com.xunmeng",
            "com.sina", "com.weico", "com.zhihu", "tv.danmaku", "com.bilibili", "com.UCMobile", "com.quark",
            "com.autonavi", "com.didi", "com.sdu.didi", "com.wuba", "com.youku", "com.qiyi", "com.hunantv",
            "com.douban", "com.coolapk", "com.ximalaya", "com.kugou", "com.cainiao", "ctrip.android.view",
            "com.Qunar", "com.tongcheng", "com.hpbr.bosszhipin", "com.achievo.vipshop", "com.suning", "com.zhangyue",
            "com.duokan", "com.moji", "com.qihoo", "com.qihoo360", "cn.wps", "com.kingsoft", "com.yy", "com.duowan",
            "com.douyu", "com.iflytek", "com.iflyrec", "com.sohu", "com.sogou", "com.meitu", "com.mt.mtxx",
            "com.moonshot", "com.deepseek", "com.wandoujia", "com.xunlei", "com.alicloud", "com.ylmf", "com.snda",
            "com.miHoYo", "com.mihoyo", "com.hypergryph",
            // phone makers' own apps
            "com.xiaomi", "com.miui", "com.mi", "com.huawei", "com.hihonor", "com.heytap", "com.oppo", "com.coloros",
            "com.oplus", "com.vivo", "com.bbk", "com.iqoo", "com.meizu", "com.zte", "com.lenovo",
            // carriers, banks, payment, government, logistics, health
            "com.chinamobile", "com.greenpoint.android.mc10086", "com.ct.client", "com.sinovatech.unicom",
            "com.ai.obc.cbn.app", "com.icbc", "com.chinamworld", "cmb.pb", "com.android.bankabc", "com.bankcomm",
            "com.ecitic", "com.cib", "cn.com.spdb", "cn.com.cmbc", "com.pingan", "com.unionpay", "cn.gov",
            "cn.cyberIdentity", "com.esscard", "com.MobileTicket", "com.sgcc", "com.sf.activity", "cn.dxy",
            // game accelerators (they run a VPN service for game traffic)
            "cn.wsds", "com.xunyou", "com.leigod", "com.qiyou",
    };

    /** Component class prefixes that mark a mainstream domestic app (push, login, payment, sharing). */
    private static final String[][] CN_STRONG = {
            {"com.xiaomi.push.", "小米推送"}, {"com.xiaomi.mipush.", "小米推送"},
            {"com.heytap.msp.", "OPPO 推送"}, {"com.heytap.mcssdk.", "OPPO 推送"}, {"com.coloros.mcssdk.", "OPPO 推送"},
            {"com.vivo.push.", "vivo 推送"}, {"com.meizu.cloud.pushsdk.", "魅族推送"}, {"com.hihonor.push.", "荣耀推送"},
            {"com.igexin.", "个推"}, {"com.getui.", "个推"}, {"cn.jpush.", "极光推送"}, {"cn.jiguang.", "极光"},
            {"com.umeng.message.", "友盟推送"}, {"com.taobao.accs.", "阿里推送"}, {"com.taobao.agoo.", "阿里推送"},
            {"org.android.agoo.", "阿里推送"}, {"com.tencent.android.tpush.", "腾讯推送"}, {"com.tencent.tpns.", "腾讯推送"},
            {"com.alibaba.sdk.android.push.", "阿里云推送"}, {"com.baidu.android.pushservice.", "百度推送"},
            {"com.tencent.tauth.", "QQ SDK"}, {"com.tencent.connect.", "QQ SDK"}, {"com.tencent.open.", "QQ SDK"},
            {"com.alipay.sdk.", "支付宝 SDK"}, {"com.alipay.android.", "支付宝 SDK"}, {"com.sina.weibo.sdk.", "微博 SDK"},
            {"com.unionpay.", "银联 SDK"}, {"com.cmic.sso.sdk.", "运营商一键登录"}, {"com.mobile.auth.", "运营商一键登录"},
            {"cn.sharesdk.", "ShareSDK"}, {"com.mob.", "MobTech"},
    };

    /** Weaker domestic markers (crash reporting, web view, maps, ads, analytics, IM): only a hint. */
    private static final String[][] CN_WEAK = {
            {"com.tencent.bugly.", "Bugly"}, {"com.tencent.smtt.", "腾讯 X5"}, {"com.tencent.tinker.", "Tinker"},
            {"com.tencent.qcloud.", "腾讯云"}, {"com.tencent.liteav.", "腾讯云视频"}, {"com.amap.api.", "高德 SDK"},
            {"com.baidu.location.", "百度定位"}, {"com.baidu.mobads.", "百度广告"}, {"com.qq.e.", "腾讯广告"},
            {"com.kwad.", "快手广告"}, {"com.sigmob.", "Sigmob 广告"}, {"com.umeng.", "友盟"},
            {"com.alibaba.sdk.android.", "阿里云 SDK"}, {"com.netease.nimlib.", "网易云信"}, {"com.hyphenate.", "环信"},
            {"io.rong.", "融云"},
    };

    /** App-cloning / virtual-machine frameworks, by the components they declare. */
    private static final String[][] VIRTUAL = {
            {"com.lody.virtual.", "VirtualApp"}, {"io.virtualapp.", "VirtualApp"}, {"com.lbe.doubleagent.", "LBE 多开"},
            {"com.excelliance.kxqp.", "卓盟多开"},
    };

    /** Files that only proxy clients ship: routing databases and proxy cores. */
    private static final Set<String> PROXY_FILES = new HashSet<>(Arrays.asList(
            "geoip.dat", "geosite.dat", "geoip.db", "geosite.db", "geoip.metadb", "Country.mmdb", "country.mmdb",
            "libclash.so", "libmihomo.so", "libxray.so", "libv2ray.so", "libsingbox.so", "libhysteria.so",
            "libhysteria2.so", "libtrojan.so", "libss-local.so", "libsslocal.so", "libnaive.so", "libtuic.so"));

    private static final Set<String> FCM = new HashSet<>(Arrays.asList(
            "com.google.firebase.messaging.FirebaseMessagingService",
            "com.google.firebase.iid.FirebaseInstanceIdReceiver"));

    private static final Map<String, String> FOREIGN_STORES = new HashMap<>();
    private static final Map<String, String> DOMESTIC_STORES = new HashMap<>();

    static {
        FOREIGN_STORES.put("com.android.vending", "Google Play");
        FOREIGN_STORES.put("com.aurora.store", "Aurora Store");
        FOREIGN_STORES.put("org.fdroid.fdroid", "F-Droid");
        FOREIGN_STORES.put("org.fdroid.basic", "F-Droid");
        FOREIGN_STORES.put("com.looker.droidify", "Droid-ify");
        FOREIGN_STORES.put("com.machiav3lli.fdroid", "Neo Store");
        // Official domestic stores only (not community ones that also carry root tools).
        DOMESTIC_STORES.put("com.xiaomi.market", "小米应用商店");
        DOMESTIC_STORES.put("com.huawei.appmarket", "华为应用市场");
        DOMESTIC_STORES.put("com.hihonor.appmarket", "荣耀应用市场");
        DOMESTIC_STORES.put("com.heytap.market", "OPPO 软件商店");
        DOMESTIC_STORES.put("com.oppo.market", "OPPO 软件商店");
        DOMESTIC_STORES.put("com.bbk.appstore", "vivo 应用商店");
        DOMESTIC_STORES.put("com.tencent.android.qqdownloader", "应用宝");
        DOMESTIC_STORES.put("com.baidu.appsearch", "百度手机助手");
        DOMESTIC_STORES.put("com.qihoo.appstore", "360 手机助手");
        DOMESTIC_STORES.put("com.meizu.mstore", "魅族应用商店");
    }

    private static final String[] ALWAYS_PROTECTED = {
            Prefs.MODULE, Prefs.TARGET, "android",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.google.android.webview", "com.android.webview", "com.google.android.trichromelibrary",
            "com.google.android.ext.services", "com.google.android.ext.shared",
    };

    private static final String VPN_PERMISSION = "android.permission.BIND_VPN_SERVICE";
    private static final String SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23";
    private static final String[] XPOSED_ENTRIES = {"assets/xposed_init", "META-INF/xposed/java_init.list"};

    static boolean listed(String[] list, String pkg) {
        for (String e : list) {
            if (pkg.equals(e) || (pkg.startsWith(e) && pkg.length() > e.length() && pkg.charAt(e.length()) == '.')) {
                return true;
            }
        }
        return false;
    }

    /** Never hidden automatically or by import: this module, the target, the launcher, WebView, Play services. */
    static Set<String> protectedPackages(Context ctx) {
        Set<String> p = new HashSet<>(Arrays.asList(ALWAYS_PROTECTED));
        PackageManager pm = ctx.getPackageManager();
        try {
            ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (home != null && home.activityInfo != null) p.add(home.activityInfo.packageName);
        } catch (Throwable ignored) {
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                PackageInfo wv = android.webkit.WebView.getCurrentWebViewPackage();
                if (wv != null && wv.packageName != null) p.add(wv.packageName);
            } catch (Throwable ignored) {
            }
        }
        return p;
    }

    // ------------------------------------------------------------------------------------------
    // What the device can tell about one app

    static final class Facts {
        boolean vpn;
        boolean xposed;
        boolean fcm;
        boolean shizuku;
        boolean miPush;
        String proxyCore;
        String virtual;
        String cnStrong;
        String cnWeak;
        /** Some lookups failed, so a missing signal proves nothing. */
        boolean incomplete;
        String installer;
    }

    /** Cheap check, done before the lists: module metadata, or the entry files of a module. */
    static boolean declaresXposed(PackageManager pm, ApplicationInfo ai) {
        try {
            Bundle md = pm.getApplicationInfo(ai.packageName, PackageManager.GET_META_DATA).metaData;
            if (md != null && (md.containsKey("xposedmodule") || md.containsKey("xposedminversion"))) return true;
        } catch (Throwable ignored) {
        }
        if (ai.sourceDir == null) return false;
        try (ZipFile z = new ZipFile(ai.sourceDir)) {
            for (String e : XPOSED_ENTRIES) if (z.getEntry(e) != null) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    static Facts inspect(PackageManager pm, ApplicationInfo ai) {
        Facts f = new Facts();
        String pkg = ai.packageName;
        int disabled = PackageManager.MATCH_DISABLED_COMPONENTS;
        PackageInfo comps;
        try {
            comps = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS
                    | PackageManager.GET_PROVIDERS | PackageManager.GET_PERMISSIONS | disabled);
        } catch (Throwable t) {
            // Possibly too large for one binder transaction: ask for each part on its own.
            comps = new PackageInfo();
            try {
                comps.services = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES | disabled).services;
            } catch (Throwable e) {
                f.incomplete = true;
            }
            try {
                comps.receivers = pm.getPackageInfo(pkg, PackageManager.GET_RECEIVERS | disabled).receivers;
            } catch (Throwable e) {
                f.incomplete = true;
            }
            try {
                comps.providers = pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS | disabled).providers;
            } catch (Throwable e) {
                f.incomplete = true;
            }
            try {
                comps.requestedPermissions = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions;
            } catch (Throwable e) {
                f.incomplete = true;
            }
        }
        if (comps.services != null) {
            for (ServiceInfo s : comps.services) {
                if (VPN_PERMISSION.equals(s.permission)) f.vpn = true;
                component(f, s.name);
            }
        }
        if (comps.receivers != null) for (ActivityInfo r : comps.receivers) component(f, r.name);
        if (comps.providers != null) for (ProviderInfo p : comps.providers) component(f, p.name);
        if (comps.requestedPermissions != null) {
            for (String p : comps.requestedPermissions) if (SHIZUKU_PERMISSION.equals(p)) f.shizuku = true;
        }
        try {
            PackageInfo acts = pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES | disabled);
            if (acts.activities != null) {
                for (ActivityInfo a : acts.activities) {
                    if (f.cnStrong == null && a.name != null && (a.name.endsWith(".wxapi.WXEntryActivity")
                            || a.name.endsWith(".wxapi.WXPayEntryActivity"))) {
                        f.cnStrong = "微信 SDK";
                    }
                    component(f, a.name);
                }
            }
        } catch (Throwable t) {
            f.incomplete = true;
        }
        try {
            Bundle md = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData;
            if (md != null && (md.containsKey("xposedmodule") || md.containsKey("xposedminversion"))) f.xposed = true;
        } catch (Throwable t) {
            f.incomplete = true;
        }
        scanApk(f, ai.sourceDir);
        if (ai.splitSourceDirs != null) for (String split : ai.splitSourceDirs) scanApk(f, split);
        f.installer = installer(pm, pkg);
        return f;
    }

    private static void component(Facts f, String cls) {
        if (cls == null) return;
        if (!f.fcm && FCM.contains(cls)) f.fcm = true;
        if (!f.miPush && (cls.startsWith("com.xiaomi.push.") || cls.startsWith("com.xiaomi.mipush."))) f.miPush = true;
        if (f.cnStrong == null) f.cnStrong = match(CN_STRONG, cls);
        if (f.cnWeak == null) f.cnWeak = match(CN_WEAK, cls);
        if (f.virtual == null) f.virtual = match(VIRTUAL, cls);
    }

    private static String match(String[][] table, String cls) {
        for (String[] row : table) if (cls.startsWith(row[0])) return row[1];
        return null;
    }

    /** One pass over the APK's file list: module entry files, proxy cores and routing databases. */
    private static void scanApk(Facts f, String apk) {
        if (apk == null) return;
        try (ZipFile z = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = z.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!f.xposed) for (String e : XPOSED_ENTRIES) if (e.equals(name)) f.xposed = true;
                if (f.proxyCore == null) {
                    String base = name.substring(name.lastIndexOf('/') + 1);
                    if (PROXY_FILES.contains(base)) f.proxyCore = base;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private static String installer(PackageManager pm, String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) return pm.getInstallSourceInfo(pkg).getInstallingPackageName();
            return pm.getInstallerPackageName(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the app receives Mi Push - the one push service this target delivers. */
    static boolean usesMiPush(PackageManager pm, String pkg) {
        int flags = PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.MATCH_DISABLED_COMPONENTS;
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, flags);
            Facts f = new Facts();
            if (pi.services != null) for (ServiceInfo s : pi.services) component(f, s.name);
            if (pi.receivers != null) for (ActivityInfo r : pi.receivers) component(f, r.name);
            return f.miPush;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Decisions

    private static Verdict pick(Kind kind, String reason) {
        return new Verdict(Outcome.PICK, kind, reason, false);
    }

    private static Verdict domestic(String reason) {
        return new Verdict(Outcome.DOMESTIC, null, reason, true);
    }

    private static Verdict of(Outcome outcome) {
        return new Verdict(outcome, null, null, false);
    }

    static Verdict judge(PackageManager pm, String pkg, Set<String> protectedSet) {
        if (protectedSet.contains(pkg)) return of(Outcome.PROTECTED);
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (Throwable t) {
            return of(Outcome.FAILED);
        }
        if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            return of(Outcome.SYSTEM);
        }
        if (declaresXposed(pm, ai)) return pick(Kind.XPOSED, "Xposed 模块");
        Verdict byName = byLists(pkg);
        return byName != null ? byName : decide(inspect(pm, ai));
    }

    /** The explicit lists, consulted before anything the app itself declares (except module metadata). */
    static Verdict byLists(String pkg) {
        if (listed(ROOT, pkg)) return pick(Kind.ROOT, "内置名单");
        if (listed(FOREIGN, pkg)) return pick(Kind.FOREIGN, "内置名单");
        if (listed(DOMESTIC, pkg)) return domestic("国内应用名单");
        return null;
    }

    /** What the app declares, in the order described on the class. */
    static Verdict decide(Facts f) {
        if (f.xposed) return pick(Kind.XPOSED, "Xposed 模块");
        if (f.proxyCore != null) return pick(Kind.VPN, "内置代理内核 / 分流规则（" + f.proxyCore + "）");
        if (f.virtual != null) return pick(Kind.ROOT, "应用分身 / 虚拟机框架（" + f.virtual + "）");
        if (f.cnStrong != null) return domestic(f.cnStrong);
        String domesticStore = f.installer == null ? null : DOMESTIC_STORES.get(f.installer);
        if (domesticStore != null) return domestic("从" + domesticStore + "安装");
        if (f.vpn) return pick(Kind.VPN, "声明了 VPN 服务");
        if (f.shizuku) return pick(Kind.ROOT, "申请了 Shizuku 权限");
        if (f.incomplete) return of(Outcome.FAILED);
        if (f.fcm) return pick(Kind.FOREIGN_INFERRED, "使用 FCM 推送");
        String foreignStore = f.installer == null ? null : FOREIGN_STORES.get(f.installer);
        if (foreignStore != null) return pick(Kind.FOREIGN_INFERRED, "从 " + foreignStore + " 安装");
        String reason = "侧载安装";
        if (f.cnWeak != null) reason += " · 含 " + f.cnWeak + "（可能是国内开发者的应用）";
        return new Verdict(Outcome.UNKNOWN, Kind.UNKNOWN, reason, false);
    }

    /**
     * Settings screen. Not hidden yet: suggestions (minus what the user turned down before). Hidden:
     * a second look only at essential components, at domestic apps that auto-select or the hook ticked
     * (not the user), and at apps that receive Mi Push - and never again at what the user chose to keep.
     */
    static Result classify(Context ctx, Collection<String> packages, Set<String> hidden, Set<String> declined,
                           Set<String> autoAdded, Set<String> kept) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> prot = protectedPackages(ctx);
        Result r = new Result();
        for (String pkg : packages) {
            if (pkg == null) continue;
            if (hidden.contains(pkg)) {
                if (kept.contains(pkg)) continue;
                if (prot.contains(pkg)) {
                    r.review.add(new Pick(pkg, label(pm, pkg), null, "关键组件（桌面 / WebView / Play 服务），隐藏可能出问题", true));
                    continue;
                }
                ApplicationInfo ai;
                try {
                    ai = pm.getApplicationInfo(pkg, 0);
                } catch (Throwable t) {
                    continue; // not installed
                }
                if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                // Only what auto-select or the hook ticked is re-judged; the user's own picks are not questioned.
                if (autoAdded.contains(pkg)) {
                    Verdict v = judge(pm, pkg, prot);
                    if (v.outcome == Outcome.DOMESTIC) {
                        r.review.add(new Pick(pkg, label(pm, pkg), null, "自动勾选的，但判断为国内应用 · " + v.reason, true));
                        continue;
                    }
                }
                if (usesMiPush(pm, pkg)) {
                    r.review.add(new Pick(pkg, label(pm, pkg), null, "⚠ 用小米推送：隐藏后它的通知可能收不到", false));
                }
                continue;
            }
            if (declined.contains(pkg)) {
                r.declined++;
                continue;
            }
            Verdict v = judge(pm, pkg, prot);
            switch (v.outcome) {
                case PICK:
                    r.picks.add(new Pick(pkg, label(pm, pkg), v.kind, v.reason, true));
                    break;
                case UNKNOWN:
                    r.unknown.add(new Pick(pkg, label(pm, pkg), Kind.UNKNOWN, v.reason, false));
                    break;
                case DOMESTIC:
                    r.domestic++;
                    break;
                case SYSTEM:
                    r.system++;
                    break;
                case PROTECTED:
                    r.protectedApps++;
                    break;
                default:
                    r.failed++;
            }
        }
        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(r.picks, (a, b) -> {
            int c = a.kind.ordinal() - b.kind.ordinal();
            return c != 0 ? c : collator.compare(a.label, b.label);
        });
        Collections.sort(r.unknown, (a, b) -> collator.compare(a.label, b.label));
        Collections.sort(r.review, (a, b) -> {
            if (a.preChecked != b.preChecked) return a.preChecked ? -1 : 1;
            return collator.compare(a.label, b.label);
        });
        return r;
    }

    /** Hook: whether an app that was just installed should be hidden right away. */
    static boolean shouldHideNewApp(Context ctx, String pkg, boolean includeUnknown) {
        Verdict v = judge(ctx.getPackageManager(), pkg, protectedPackages(ctx));
        return v.outcome == Outcome.PICK || (includeUnknown && v.outcome == Outcome.UNKNOWN);
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
        } catch (Throwable t) {
            return pkg;
        }
    }
}
