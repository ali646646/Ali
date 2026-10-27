package com.example.util

object PersianFormatters {
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun Int.toPersianDigits(): String = this.toString().toPersianDigits()

    fun String.toPersianDigits(): String {
        val sb = StringBuilder(length)
        for (ch in this) {
            when (ch) {
                in '0'..'9' -> sb.append(persianDigits[ch - '0'])
                in '٠'..'٩' -> sb.append(persianDigits[ch - '٠'])
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun String.normalizeDigitsToAscii(): String {
        val sb = StringBuilder(length)
        for (ch in this) {
            when (ch) {
                in '۰'..'۹' -> sb.append(('0'.code + (ch - '۰')).toChar())
                in '٠'..'٩' -> sb.append(('0'.code + (ch - '٠')).toChar())
                in '0'..'9' -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
