package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Elix's quick commands: answered at once on the headset, without the network — greetings, time,
 * date, battery, and "open …", "take a photo", "recenter"… Everything else goes to the model.
 */
object ElixCommands {
    class Answer(val text: String, val action: String? = null)

    // VR home ids (VrHomeActivity).
    private val OPEN = listOf(
        listOf("браузер", "browser", "navegador", "интернет") to "own:browser",
        listOf("фото", "галере", "photos", "fotos", "fotografias") to "own:photos",
        listOf("настройк", "settings", "ajustes", "definições") to "own:settings",
        listOf("магазин", "store", "loja") to "own:store",
        listOf("звонк", "calls", "chamadas") to "own:calls",
        listOf("android", "андроид", "приложени") to "own:android",
    )

    fun answer(context: Context, question: String): Answer? {
        val q = question.lowercase(Locale.ROOT).trim().trimEnd('?', '!', '.')
        fun has(vararg words: String) = words.any { q.contains(it) }
        val name = Settings.userName(context).ifBlank { null }
        return when {
            has("привет", "здравств", "hello", "hi elix", "hey", "olá", "ola", "oi ") || q == "hi" || q == "oi" ->
                Answer(tr("Привет") + (name?.let { ", $it" } ?: "") + "! " + tr("Чем помочь?"))
            has("как дела", "how are you", "tudo bem", "como vai", "como estás") -> Answer(tr("Отлично! Готова помочь."))
            has("спасибо", "thank", "obrigad") -> Answer(tr("Пожалуйста!"))
            has("кто ты", "who are you", "quem é você", "quem és") -> Answer(tr("Я Elix, ассистент PhoneXR."))
            has("что ты умеешь", "помощь", "команды", "help", "what can you do", "ajuda") -> Answer(tr(
                "Быстрые команды: «привет», «который час», «какое сегодня число», «заряд», «открой браузер / фото / " +
                    "настройки / магазин / звонки», «сделай фото», «выровняй», «граница», «выйди из VR». " +
                    "На остальное отвечу с помощью нейросети."))
            has("который час", "сколько сейчас времени", "what time is it", "que horas são", "que horas sao") || q == "сколько времени" || q == "время" ->
                Answer(tr("Сейчас") + " " + DateFormat.getTimeInstance(DateFormat.SHORT, locale()).format(Date()))
            has("какое сегодня число", "какой сегодня день", "какая сегодня дата", "what day is it", "what's the date", "today's date", "que dia é hoje", "que dia e hoje") ->
                Answer(DateFormat.getDateInstance(DateFormat.FULL, locale()).format(Date()).replaceFirstChar { it.uppercase() })
            has("какой заряд", "сколько заряда", "заряд батареи", "battery level", "how much battery", "bateria") || q == "заряд" -> Answer(tr("Заряд") + ": " + battery(context) + "%")
            has("сделай фото", "сфотограф", "снимок", "take a photo", "tirar foto", "tira uma foto") -> Answer(tr("Снимаю!"), "menu:photo")
            has("выровняй", "выровнять", "recenter", "recentr") -> Answer(tr("Готово, выровняла вид."), "menu:recenter")
            has("настрой границ", "настроить границ", "set up boundary", "configurar limite") || q == "граница" -> Answer(tr("Обойдите край свободного места."), "menu:boundary")
            has("выйди из vr", "выйти из vr", "exit vr", "sair do vr", "sair de vr") -> Answer(tr("Выхожу из VR."), "menu:exit")
            has("открой", "запусти", "open", "abr", "abre") -> OPEN.firstOrNull { (words, _) -> words.any { q.contains(it) } }
                ?.let { (_, id) -> Answer(tr("Открываю."), id) }
            else -> null
        }
    }

    private fun locale(): Locale = when (L10n.current) {
        L10n.Lang.RU -> Locale("ru")
        L10n.Lang.EN -> Locale.ENGLISH
        L10n.Lang.PT_BR -> Locale("pt", "BR")
        L10n.Lang.PT_PT -> Locale("pt", "PT")
    }

    private fun battery(context: Context): Int =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) } ?: 0
}
