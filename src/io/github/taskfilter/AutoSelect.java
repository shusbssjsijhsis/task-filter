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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Decides which apps to hide, using only what this device can tell about each app; nothing is sent
 * anywhere. Used by the settings screen ("auto-select") and by the hook for apps installed later.
 *
 * Order matters, and domestic evidence wins: a known root tool or foreign app (explicit lists) is
 * chosen; then a known domestic app is never chosen; then an Xposed module is chosen; then an app
 * carrying a Chinese push, login, payment or sharing SDK is domestic and never chosen - even when it
 * declares a VPN service (carriers, app stores and game helpers use one for "network acceleration")
 * or asks for Shizuku. Only after that do a VPN service, the Shizuku permission, FCM, or a Google Play
 * / F-Droid installer count. System apps, the launcher, WebView and Play services are never chosen.
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
        /** For DOMESTIC: decided by a list or a push/login/payment SDK, not only by a weaker SDK. */
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
        /** Already hidden, but domestic or essential: offered for unticking. */
        final List<Pick> untick = new ArrayList<>();
        int system;
        int domestic;
        int protectedApps;
        int failed;
    }

    // ------------------------------------------------------------------------------------------
    // Rules. A list entry matches the package itself and everything below it ("com.google" matches
    // "com.google.android.youtube"). These are generic public lists, not anyone's phone.

    private static final String[] ROOT = {
            // root managers
            "com.topjohnwu.magisk", "io.github.vvb2060.magisk", "io.github.huskydg.magisk", "me.weishu.kernelsu",
            "com.rifsxd.ksunext", "com.sukisu.ultra", "me.bmax.apatch",
            // Xposed managers and patchers, Shizuku
            "org.lsposed.manager", "org.lsposed.lspatch", "moe.shizuku.privileged.api",
            // terminals, file, package and backup tools
            "com.termux", "bin.mt.plus", "bin.mt.termex", "io.github.muntashirakon.AppManager", "com.rosan.installer.x",
            "com.aistra.hail", "com.catchingnow.icebox", "org.swiftapps.swiftbackup", "com.machiav3lli.backup",
            "com.keramidas.TitaniumBackup", "com.dp.logcatapp",
            // kernel and performance tools
            "com.github.capntrips.kernelflasher", "xzr.konabess", "com.franco.kernel", "flar2.exkernelmanager",
            "com.smartpack.kernelmanager", "com.omarea.vtools",
            // root / integrity / environment checkers
            "io.github.vvb2060.keyattestation", "io.github.vvb2060.mahoshojo", "icu.nullptr.nativetest",
            "com.zhenxi.hunter", "com.byxiaorun.detector", "rikka.safetynetchecker", "gr.nikolasspyr.integritycheck",
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
            // browsers
            "org.mozilla", "com.brave", "com.opera", "com.vivaldi", "com.kiwibrowser", "com.duckduckgo",
            "org.torproject",
            // Microsoft, Proton, password managers, authenticators
            "com.microsoft", "com.azure.authenticator", "ch.protonmail", "ch.protonvpn", "me.proton",
            "com.x8bit.bitwarden", "com.agilebits", "com.lastpass", "com.authy", "com.beemdevelopment.aegis",
            // AI assistants
            "com.openai", "com.anthropic", "ai.x.grok", "ai.perplexity",
            // media, stores, payments, productivity
            "com.spotify", "com.netflix", "com.amazon", "com.disney", "tv.twitch", "com.valvesoftware",
            "com.paypal", "com.revolut", "com.dropbox", "md.obsidian", "com.slack", "org.wikipedia", "org.fdroid",
            "com.aurora.store", "com.looker.droidify",
            // crypto
            "com.binance", "com.okinc", "com.bybit", "com.bitget", "com.coinbase", "com.kraken", "io.metamask",
            "com.wallet.crypto.trustapp", "org.toshi",
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

    /** Domestic apps: never chosen, whatever else they declare. Consulted after the two lists above. */
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
            "mark.via", "io.legado", "com.flyersoft", "com.mojitec", "com.miHoYo", "com.mihoyo", "com.hypergryph",
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

    /** Weaker domestic markers (crash reporting, web view, maps, ads, analytics, IM). */
    private static final String[][] CN_WEAK = {
            {"com.tencent.bugly.", "Bugly"}, {"com.tencent.smtt.", "腾讯 X5"}, {"com.tencent.tinker.", "Tinker"},
            {"com.tencent.qcloud.", "腾讯云"}, {"com.tencent.liteav.", "腾讯云视频"}, {"com.amap.api.", "高德 SDK"},
            {"com.baidu.location.", "百度定位"}, {"com.baidu.mobads.", "百度广告"}, {"com.qq.e.", "腾讯广告"},
            {"com.kwad.", "快手广告"}, {"com.sigmob.", "Sigmob 广告"}, {"com.umeng.", "友盟"},
            {"com.alibaba.sdk.android.", "阿里云 SDK"}, {"com.netease.nimlib.", "网易云信"}, {"com.hyphenate.", "环信"},
            {"io.rong.", "融云"},
    };

    private static final Set<String> FCM = new HashSet<>(Arrays.asList(
            "com.google.firebase.messaging.FirebaseMessagingService",
            "com.google.firebase.iid.FirebaseInstanceIdReceiver"));

    private static final Map<String, String> FOREIGN_STORES = new HashMap<>();

    static {
        FOREIGN_STORES.put("com.android.vending", "Google Play");
        FOREIGN_STORES.put("com.aurora.store", "Aurora Store");
        FOREIGN_STORES.put("org.fdroid.fdroid", "F-Droid");
        FOREIGN_STORES.put("org.fdroid.basic", "F-Droid");
        FOREIGN_STORES.put("com.looker.droidify", "Droid-ify");
        FOREIGN_STORES.put("com.machiav3lli.fdroid", "Neo Store");
    }

    private static final String[] ALWAYS_PROTECTED = {
            Prefs.MODULE, Prefs.TARGET, "android",
            "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
            "com.google.android.webview", "com.android.webview", "com.google.android.trichromelibrary",
            "com.google.android.ext.services", "com.google.android.ext.shared",
    };

    private static final String VPN_PERMISSION = "android.permission.BIND_VPN_SERVICE";
    private static final String SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23";

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
        String cnStrong;
        String cnWeak;
        /** Some lookups failed, so a missing signal proves nothing. */
        boolean incomplete;
        String installer;
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
        if (!f.xposed) f.xposed = apkDeclaresXposed(ai.sourceDir);
        f.installer = installer(pm, pkg);
        return f;
    }

    private static void component(Facts f, String cls) {
        if (cls == null) return;
        if (!f.fcm && FCM.contains(cls)) f.fcm = true;
        if (f.cnStrong == null) f.cnStrong = match(CN_STRONG, cls);
        if (f.cnWeak == null) f.cnWeak = match(CN_WEAK, cls);
    }

    private static String match(String[][] table, String cls) {
        for (String[] row : table) if (cls.startsWith(row[0])) return row[1];
        return null;
    }

    /** Legacy modules ship assets/xposed_init; modern (libxposed) ones META-INF/xposed/java_init.list. */
    private static boolean apkDeclaresXposed(String apk) {
        if (apk == null) return false;
        try (ZipFile z = new ZipFile(apk)) {
            return z.getEntry("assets/xposed_init") != null || z.getEntry("META-INF/xposed/java_init.list") != null;
        } catch (Throwable t) {
            return false;
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

    // ------------------------------------------------------------------------------------------
    // Decisions

    private static Verdict pick(Kind kind, String reason) {
        return new Verdict(Outcome.PICK, kind, reason, false);
    }

    private static Verdict domestic(String reason, boolean strong) {
        return new Verdict(Outcome.DOMESTIC, null, reason, strong);
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
        Verdict byName = byLists(pkg);
        return byName != null ? byName : decide(inspect(pm, ai));
    }

    /** The explicit lists, consulted before anything the app itself declares. */
    static Verdict byLists(String pkg) {
        if (listed(ROOT, pkg)) return pick(Kind.ROOT, "内置名单");
        if (listed(FOREIGN, pkg)) return pick(Kind.FOREIGN, "内置名单");
        if (listed(DOMESTIC, pkg)) return domestic("国内应用名单", true);
        return null;
    }

    /** What the app declares; domestic evidence outranks a VPN service or the Shizuku permission. */
    static Verdict decide(Facts f) {
        if (f.xposed) return pick(Kind.XPOSED, "Xposed 模块");
        if (f.cnStrong != null) return domestic(f.cnStrong, true);
        if (f.vpn) return pick(Kind.VPN, "声明了 VPN 服务");
        if (f.shizuku) return pick(Kind.ROOT, "申请了 Shizuku 权限");
        if (f.cnWeak != null) return domestic(f.cnWeak, false);
        if (f.incomplete) return of(Outcome.FAILED);
        if (f.fcm) return pick(Kind.FOREIGN_INFERRED, "使用 FCM 推送");
        String store = f.installer == null ? null : FOREIGN_STORES.get(f.installer);
        if (store != null) return pick(Kind.FOREIGN_INFERRED, "从 " + store + " 安装");
        return new Verdict(Outcome.UNKNOWN, Kind.UNKNOWN, "没有可判断的特征", false);
    }

    /**
     * Settings screen: suggestions for everything not hidden yet, and - among what is hidden - the
     * domestic or essential apps the user may want to untick.
     */
    static Result classify(Context ctx, Collection<String> packages, Set<String> hidden) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> prot = protectedPackages(ctx);
        Result r = new Result();
        for (String pkg : packages) {
            if (pkg == null) continue;
            Verdict v = judge(pm, pkg, prot);
            if (hidden.contains(pkg)) {
                if (v.outcome == Outcome.PROTECTED) {
                    r.untick.add(new Pick(pkg, label(pm, pkg), null, "关键组件（桌面 / WebView / Play 服务），隐藏可能出问题", true));
                } else if (v.outcome == Outcome.DOMESTIC) {
                    r.untick.add(new Pick(pkg, label(pm, pkg), null,
                            (v.strong ? "国内应用 · " : "可能是国内开发者的应用 · ") + v.reason, v.strong));
                }
                continue;
            }
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
        Collections.sort(r.untick, (a, b) -> {
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
