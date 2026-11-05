package com.ethic.vpn.core

/** Russian apps that the split tunneling screen can select in one tap: banks, government services, marketplaces. */
object RussianApps {
    private val KNOWN = setOf(
        // Banks and payments
        "ru.sberbankmobile", "com.idamob.tinkoff.android", "ru.vtb24.mobilebanking.android",
        "ru.alfabank.mobile.android", "ru.raiffeisen.mobile.new", "ru.gazprombank.android.mobilebank.app",
        "ru.psbank.mobile", "ru.rosbank.android", "ru.sovcomcard.halva", "ru.mkb.mobile", "ru.pochta.bank",
        "ru.nspk.mirpay", "ru.ozon.fintech.finance",
        // Government services and taxes
        "ru.rostel", "com.gnivts.selfemployed", "ru.fns.lkfl", "ru.mos.app", "ru.mosreg.ekjp",
        // Social networks, messengers, mail
        "com.vkontakte.android", "com.vk.vkvideo", "ru.ok.android", "ru.mail.mailapp", "ru.oneme.app",
        // Yandex
        "ru.yandex.searchplugin", "com.yandex.browser", "ru.yandex.yandexmaps", "ru.yandex.taxi",
        "ru.yandex.music", "ru.yandex.disk", "ru.yandex.mail", "ru.kinopoisk", "ru.beru.android",
        "com.yandex.lavka", "ru.foodfox.client", "ru.yandex.yandexnavi",
        // Shops and services
        "com.wildberries.ru", "ru.ozon.app.android", "com.avito.android", "ru.dublgis.dgismobile",
        "ru.megamarket.marketplace", "ru.lamoda", "com.deliveryclub", "ru.rzd.pass", "ru.aviasales",
        "com.octopod.russianpost.client.android", "ru.cian.main", "ru.hh.android", "com.punicapp.whoosh",
        // Video and music
        "ru.rutube.app", "ru.ivi.client", "ru.more.play", "ru.start.androidmobile", "ru.zvuk.app",
        // Mobile operators
        "ru.mts.mymts", "ru.megafon.mlk", "ru.beeline.services", "ru.tele2.mytele2",
    )

    fun isRussian(packageName: String): Boolean = packageName in KNOWN || packageName.startsWith("ru.")
}
