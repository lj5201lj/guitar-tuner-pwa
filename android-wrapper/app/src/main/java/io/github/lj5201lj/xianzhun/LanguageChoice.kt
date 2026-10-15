package io.github.lj5201lj.xianzhun

enum class LanguageChoice(val tag: String) {
    SYSTEM(""), SIMPLIFIED_CHINESE("zh-Hans"), ENGLISH("en");

    companion object {
        fun fromTag(tag: String): LanguageChoice = when (tag.substringBefore('-').lowercase()) {
            "zh" -> SIMPLIFIED_CHINESE
            "en" -> ENGLISH
            else -> SYSTEM
        }
    }
}
