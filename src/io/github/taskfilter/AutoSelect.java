package io.github.taskfilter;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * Decides which apps to hide, using only what this device can tell about each app; nothing is sent
 * anywhere. Used by the settings screen ("auto-select") and by the hook for apps installed later.
 *
 * Never chosen: system apps (they ship with the ROM, and the target may rely on them), the current
 * launcher, the WebView provider and Google Play services, and - unless it is a VPN app, an Xposed
 * module, a root tool or a listed foreign app - any app that carries a Chinese push SDK (Mi Push or
 * another vendor or aggregator push service): such an app is domestic, and hiding it from Xiaomi
 * Service Framework could stop its notifications.
 */
final class AutoSelect {
    private AutoSelect() {}

    enum Kind {
        VPN("代理 / VPN"),
        XPOSED("Xposed 模块"),
        ROOT("root 及相关工具"),
        FOREIGN("境外应用"),
        FOREIGN_INFERRED("境外应用（按 FCM 推送或 Google Play / F-Droid 安装来源推断）"),
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
        final boolean cnPush;

        Verdict(Outcome outcome, Kind kind, boolean cnPush) {
            this.outcome = outcome;
            this.kind = kind;
            this.cnPush = cnPush;
        }
    }

    static final class Pick {
        final String pkg;
        final String label;
        final Kind kind;
        /** Carries a Chinese push SDK: hiding it may stop its notifications. */
        final boolean cnPush;

        Pick(String pkg, String label, Kind kind, boolean cnPush) {
            this.pkg = pkg;
            this.label = label;
            this.kind = kind;
            this.cnPush = cnPush;
        }
    }

    static final class Result {
        final List<Pick> picks = new ArrayList<>();
        final List<Pick> unknown = new ArrayList<>();
        int system;
        int domestic;
        int protectedApps;
        int failed;
    }

    // ------------------------------------------------------------------------------------------
    // Rules. An entry matches the package itself and everything below it ("com.google" matches
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
            // proxy and VPN clients (usually caught by their VPN service already)
            "com.github.shadowsocks", "com.v2ray", "com.github.kr328.clash", "com.github.metacubex",
            "io.nekohasekai", "moe.nb4a", "com.follow.clash", "app.hiddify", "com.hiddify", "io.geph",
            "org.outline", "com.psiphon3", "org.getlantern", "com.nthlink", "com.cloudflare.onedotonedotonedotone",
            "net.mullvad", "com.expressvpn", "com.nordvpn", "com.surfshark", "com.windscribe", "de.blinkt.openvpn",
            "net.openvpn", "com.wireguard",
    };

    /** Domestic apps that may lack a detectable push SDK; consulted after the lists above. */
    private static final String[] DOMESTIC = {
            "com.tencent", "com.qq", "com.alibaba", "com.taobao", "com.alipay", "com.eg.android.AlipayGphone",
            "com.jingdong", "com.jd", "com.sankuai", "com.meituan", "com.dianping", "com.ss.android", "com.bytedance",
            "com.larus", "com.lemon.lv", "com.xingin", "com.smile.gifmaker", "com.kuaishou", "com.baidu",
            "com.netease", "com.sina", "com.weico", "com.zhihu", "tv.danmaku", "com.bilibili", "com.xunmeng",
            "com.UCMobile", "com.quark", "com.autonavi", "com.didi", "com.sdu.didi", "com.wuba", "com.youku",
            "com.qiyi", "com.hunantv", "com.douban", "com.coolapk", "com.xiaomi", "com.miui", "com.huawei",
            "com.hihonor", "com.heytap", "com.oppo", "com.coloros", "com.vivo", "com.bbk", "com.meizu", "com.icbc",
            "com.chinamworld", "cmb.pb", "com.chinamobile", "com.greenpoint.android.mc10086", "com.ct.client",
            "com.sinovatech.unicom", "cn.gov", "cn.cyberIdentity", "cn.wps", "com.unionpay", "com.MobileTicket",
            "com.sgcc", "com.duowan", "com.yy", "com.iflytek", "com.iflyrec", "com.moonshot", "com.deepseek",
            "com.meitu", "com.mt.mtxx", "com.ximalaya", "com.kugou", "com.cainiao", "ctrip.android.view",
            "com.Qunar", "com.hpbr.bosszhipin", "com.alicloud", "com.ylmf", "com.snda", "com.kingsoft",
            "com.sf.activity", "cn.dxy", "com.wandoujia", "com.xunlei", "com.achievo.vipshop", "com.suning",
    };

    /** Component class prefixes of Chinese push SDKs (Mi Push first: the one this target delivers). */
    private static final String[] CN_PUSH = {
            "com.xiaomi.push.", "com.xiaomi.mipush.",
            "com.heytap.msp.", "com.heytap.mcssdk.", "com.coloros.mcssdk.",
            "com.vivo.push.", "com.meizu.cloud.pushsdk.", "com.hihonor.push.",
            "com.igexin.", "com.getui.", "cn.jpush.", "cn.jiguang.",
            "com.umeng.message.", "com.taobao.accs.", "com.taobao.agoo.", "org.android.agoo.",
            "com.tencent.android.tpush.", "com.tencent.tpns.",
            "com.alibaba.sdk.android.push.", "com.baidu.android.pushservice.",
    };

    private static final Set<String> FCM = new HashSet<>(Arrays.asList(
            "com.google.firebase.messaging.FirebaseMessagingService",
            "com.google.firebase.iid.FirebaseInstanceIdReceiver"));

    private static final Set<String> FOREIGN_STORES = new HashSet<>(Arrays.asList(
            "com.android.vending", "com.aurora.store", "org.fdroid.fdroid", "org.fdroid.basic",
            "com.looker.droidify", "com.machiav3lli.fdroid"));

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

    /** Packages that are never hidden automatically or by import: this module, the target, the launcher, WebView, Play services. */
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
        boolean cnPush;
        boolean fcm;
        boolean shizuku;
        /** Some lookups failed, so a missing signal proves nothing. */
        boolean incomplete;
        String installer;
    }

    static Facts inspect(PackageManager pm, ApplicationInfo ai) {
        Facts f = new Facts();
        String pkg = ai.packageName;
        int disabled = PackageManager.MATCH_DISABLED_COMPONENTS;
        // Separate queries keep each answer small enough for one binder transaction.
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SERVICES | disabled);
            if (pi.services != null) {
                for (ServiceInfo s : pi.services) {
                    if (VPN_PERMISSION.equals(s.permission)) f.vpn = true;
                    component(f, s.name);
                }
            }
        } catch (Throwable t) {
            f.incomplete = true;
        }
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_RECEIVERS | disabled);
            if (pi.receivers != null) for (ActivityInfo r : pi.receivers) component(f, r.name);
        } catch (Throwable t) {
            f.incomplete = true;
        }
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) if (SHIZUKU_PERMISSION.equals(p)) f.shizuku = true;
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
        if (f.cnPush) return;
        for (String p : CN_PUSH) {
            if (cls.startsWith(p)) {
                f.cnPush = true;
                return;
            }
        }
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

    static Verdict judge(PackageManager pm, String pkg, Set<String> protectedSet) {
        if (protectedSet.contains(pkg)) return new Verdict(Outcome.PROTECTED, null, false);
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (Throwable t) {
            return new Verdict(Outcome.FAILED, null, false);
        }
        if ((ai.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
            return new Verdict(Outcome.SYSTEM, null, false);
        }
        Facts f = inspect(pm, ai);
        Kind kind = null;
        if (f.vpn) kind = Kind.VPN;
        else if (f.xposed) kind = Kind.XPOSED;
        else if (listed(ROOT, pkg) || f.shizuku) kind = Kind.ROOT;
        else if (listed(FOREIGN, pkg)) kind = Kind.FOREIGN;
        if (kind != null) return new Verdict(Outcome.PICK, kind, f.cnPush);
        if (f.cnPush || listed(DOMESTIC, pkg)) return new Verdict(Outcome.DOMESTIC, null, f.cnPush);
        if (f.incomplete) return new Verdict(Outcome.FAILED, null, false);
        if (f.fcm || (f.installer != null && FOREIGN_STORES.contains(f.installer))) {
            return new Verdict(Outcome.PICK, Kind.FOREIGN_INFERRED, false);
        }
        return new Verdict(Outcome.UNKNOWN, Kind.UNKNOWN, false);
    }

    /** Settings screen: suggestions for everything installed that is not hidden yet. */
    static Result classify(Context ctx, Collection<String> packages, Set<String> alreadyHidden) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> prot = protectedPackages(ctx);
        Result r = new Result();
        for (String pkg : packages) {
            if (pkg == null || alreadyHidden.contains(pkg)) continue;
            Verdict v = judge(pm, pkg, prot);
            switch (v.outcome) {
                case PICK:
                    r.picks.add(new Pick(pkg, label(pm, pkg), v.kind, v.cnPush));
                    break;
                case UNKNOWN:
                    r.unknown.add(new Pick(pkg, label(pm, pkg), Kind.UNKNOWN, false));
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
