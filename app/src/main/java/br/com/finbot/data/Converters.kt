package br.com.finbot.data

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate

/**
 * Instantes em epoch millis (UTC); datas de calendário em ISO-8601 "yyyy-MM-dd",
 * que ordenam corretamente como texto.
 */
class Converters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun localDateToText(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun textToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
}
