package com.claxe.sorune

/**
 * A light ad blocker: requests a page makes to well-known ad and tracking servers are not loaded
 * (the page itself and everything else is untouched). The list is Sorune's own, kept short and
 * conservative: servers that only serve ads or trackers, nothing a site needs to work.
 */
object AdBlocker {
    private val domains = setOf(
        // Google ads
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "googletagservices.com", "pagead2.googlesyndication.com", "fundingchoicesmessages.google.com",
        // Ad exchanges and networks
        "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "pubmatic.com",
        "rubiconproject.com", "openx.net", "amazon-adsystem.com", "adsrvr.org", "casalemedia.com",
        "smartadserver.com", "adform.net", "yieldmo.com", "teads.tv", "sharethrough.com", "bidswitch.net",
        "3lift.com", "triplelift.com", "indexww.com", "media.net", "zedo.com", "adroll.com", "serving-sys.com",
        "flashtalking.com", "advertising.com", "adcolony.com", "applovin.com", "inmobi.com", "mopub.com",
        "revcontent.com", "mgid.com", "adskeeper.com", "contextweb.com", "spotxchange.com", "springserve.com",
        "lijit.com", "sovrn.com", "gumgum.com", "undertone.com", "33across.com", "sonobi.com", "districtm.io",
        "e-planning.net", "adtelligent.com", "smaato.net", "yieldlab.net", "adition.com", "admixer.net",
        "adfox.ru", "an.yandex.ru", "adriver.ru", "admatic.com.tr", "adgager.com",
        // Pop-ups and redirect ads common on reading and streaming sites
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "exoclick.com", "juicyads.com",
        "trafficjunky.net", "onclickads.net", "hilltopads.net", "clickadu.com", "adcash.com", "a-ads.com",
        "pushame.com", "richpush.co", "evadav.com", "galaksion.com", "monetag.com", "ad-maven.com", "admaven.com",
        // Tracking
        "scorecardresearch.com", "quantserve.com", "moatads.com", "hotjar.com", "mouseflow.com",
        "ads-twitter.com", "ads.linkedin.com", "bat.bing.com", "analytics.tiktok.com", "branch.io",
        "adjust.com", "appsflyer.com", "app-measurement.com", "crashlytics.com", "newrelic.com",
        "chartbeat.com", "chartbeat.net", "parsely.com", "comscore.com", "krxd.net", "bluekai.com",
        "demdex.net", "exelator.com", "rlcdn.com", "tapad.com", "agkn.com", "mathtag.com", "eyeota.net",
    )

    /** True for a host on the list or below one ("ads.example.com" when "example.com" is listed). */
    fun isBlocked(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (h in domains) return true
            val dot = h.indexOf('.')
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }
}
