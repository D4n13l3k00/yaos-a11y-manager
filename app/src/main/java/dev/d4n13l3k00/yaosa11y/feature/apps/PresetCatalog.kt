package dev.d4n13l3k00.yaosa11y.feature.apps

enum class PresetGroup(
    val title: String,
    val subtitle: String,
) {
    YANDEX(
        "Системные плагины Яндекс",
        "Home и com.yandex.tv.services выбираются только вручную",
    ),
    ANDROID(
        "Системные плагины Android",
        "Bluetooth не входит в групповой выбор",
    ),
    LIVE_TV(
        "Эфирное телевидение",
        "Выбирайте группу только если тюнер и эфирное ТВ не используются",
    ),
}

enum class PresetRisk {
    NORMAL,
    CONDITIONAL,
    CRITICAL,
}

data class PresetDefinition(
    val group: PresetGroup,
    val label: String,
    val packageName: String,
    val recommended: Boolean,
    val groupSelectable: Boolean = recommended,
    val risk: PresetRisk = PresetRisk.NORMAL,
    val warning: String? = null,
)

object PresetCatalog {
    val blockedPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.yandex.tv.services.platform",
        "dev.d4n13l3k00.yaosa11y",
    )

    val definitions = listOf(
        PresetDefinition(PresetGroup.YANDEX, "Yandex Prebuilt Stub", "com.yandex.prebuiltstub", true),
        PresetDefinition(PresetGroup.YANDEX, "Yandex IO SDK", "com.yandex.io.sdk", true),
        PresetDefinition(PresetGroup.YANDEX, "Яндекс Браузер", "com.yandex.browser.tv", true),
        PresetDefinition(PresetGroup.YANDEX, "Алиса", "com.yandex.tv.alice", true),
        PresetDefinition(PresetGroup.YANDEX, "Конфигурация производителя", "com.yandex.tv.vendor.config", true),
        PresetDefinition(PresetGroup.YANDEX, "YouTube Player", "com.yandex.tv.ytplayer", true),
        PresetDefinition(PresetGroup.YANDEX, "Яндекс Музыка", "com.yandex.tv.music", true),
        PresetDefinition(PresetGroup.YANDEX, "Видеоплеер", "com.yandex.tv.videoplayer", true),
        PresetDefinition(PresetGroup.YANDEX, "Веб-плеер", "com.yandex.tv.webplayer", true),
        PresetDefinition(
            PresetGroup.YANDEX,
            "Яндекс Home",
            "com.yandex.tv.home",
            false,
            groupSelectable = false,
            risk = PresetRisk.CRITICAL,
            warning = "Отключать только при работающем стороннем лаунчере",
        ),
        PresetDefinition(
            PresetGroup.YANDEX,
            "Отправка отчётов об ошибках",
            "com.yandex.tv.bugreportsender",
            true,
        ),
        PresetDefinition(
            PresetGroup.YANDEX,
            "Yandex TV Services",
            "com.yandex.tv.services",
            false,
            groupSelectable = false,
            risk = PresetRisk.CRITICAL,
            warning =
                "Возможны проблемы загрузки; не путать с com.yandex.tv.services.platform",
        ),
        PresetDefinition(PresetGroup.YANDEX, "Обновление лаунчера", "com.yandex.launcher.updaterapp", true),
        PresetDefinition(PresetGroup.YANDEX, "Кинопоиск", "ru.kinopoisk.yandex.tv", true),
        PresetDefinition(PresetGroup.YANDEX, "Advertising ID", "com.yandex.android.advid", true),
        PresetDefinition(PresetGroup.YANDEX, "Мастер настройки", "com.yandex.tv.setupwizard", true),
        PresetDefinition(PresetGroup.YANDEX, "Заставка Яндекс ТВ", "com.yandex.tv.daydream", true),
        PresetDefinition(PresetGroup.ANDROID, "Резервное копирование обоев", "com.android.wallpaperbackup", true),
        PresetDefinition(PresetGroup.ANDROID, "Cultraview OS Update", "com.cultraview.osupdate", true),
        PresetDefinition(PresetGroup.ANDROID, "Календарь", "com.android.calendar", true),
        PresetDefinition(PresetGroup.ANDROID, "Контакты", "com.android.contacts", true),
        PresetDefinition(PresetGroup.ANDROID, "Провайдер календаря", "com.android.providers.calendar", true),
        PresetDefinition(PresetGroup.ANDROID, "Провайдер контактов", "com.android.providers.contacts", true),
        PresetDefinition(
            PresetGroup.ANDROID,
            "MediaTek Bluetooth",
            "com.mtk.bluetooth",
            false,
            groupSelectable = false,
            risk = PresetRisk.CONDITIONAL,
            warning = "Только без Bluetooth-пульта и периферии",
        ),
        PresetDefinition(PresetGroup.ANDROID, "Системное обновление", "com.example.upgrad", true),
        PresetDefinition(PresetGroup.ANDROID, "MediaTek Android Box", "com.mediatek.androidbox", true),
        PresetDefinition(PresetGroup.ANDROID, "Пользовательский словарь", "com.android.providers.userdictionary", true),
        PresetDefinition(PresetGroup.ANDROID, "Dynamic System Updates", "com.android.dynsystem", true),
        PresetDefinition(
            PresetGroup.LIVE_TV,
            "Эфирное ТВ — тюнер",
            "com.yandex.tv.input.efir",
            false,
            groupSelectable = true,
            risk = PresetRisk.CONDITIONAL,
            warning = "Отключать только если эфирное ТВ не используется",
        ),
        PresetDefinition(
            PresetGroup.LIVE_TV,
            "Настройка и сканирование каналов",
            "com.yandex.tv.setting",
            false,
            groupSelectable = true,
            risk = PresetRisk.CONDITIONAL,
            warning = "Отключать только если эфирное ТВ не используется",
        ),
        PresetDefinition(
            PresetGroup.LIVE_TV,
            "MediaTek TV Center",
            "com.mediatek.wwtv.tvcenter",
            false,
            groupSelectable = true,
            risk = PresetRisk.CONDITIONAL,
            warning = "Отключать только если эфирное ТВ не используется",
        ),
    )
}
