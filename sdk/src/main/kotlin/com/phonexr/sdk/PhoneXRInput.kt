package com.phonexr.sdk

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress

/**
 * Читает поток PhoneXR: положение рук, жесты и кнопки Joy-Con.
 *
 * Обычный ввод (позы контроллеров, кнопки) игра получает через OpenXR. Этот класс нужен, когда
 * хочется сырые данные: например, показать ладонь или сделать свой жест.
 *
 * Данные приходят по UDP на 127.0.0.1:42425, пока работает трекинг в PhoneXR.
 * Порт занимает один клиент: если игра не видит данных, значит их уже читает другое приложение.
 */
class PhoneXRInput(port: Int = 42425) : AutoCloseable {
    data class Hand(
        /** Рука видна камере или подключён Joy-Con этой стороны. */
        val present: Boolean = false,
        /** Кулак, указательный палец, большой палец. В режиме «только руки» всегда false. */
        val fist: Boolean = false,
        val index: Boolean = false,
        val thumb: Boolean = false,
        /** Положение ладони в кадре: x и y от 0 до 1, z — близость к камере (1 — ближе всего). */
        val x: Float = .5f,
        val y: Float = .5f,
        val z: Float = .5f,
        /** Поворот от Joy-Con, если у него доступен гироскоп. Иначе единичный кватернион. */
        val qx: Float = 0f,
        val qy: Float = 0f,
        val qz: Float = 0f,
        val qw: Float = 1f,
        /** Набор битов Button: какие кнопки Joy-Con нажаты. */
        val buttons: Int = 0
    ) {
        fun isPressed(button: Button) = buttons and button.bit != 0
    }

    enum class Button(val bit: Int) {
        PRIMARY(1), SECONDARY(1 shl 1), TRIGGER(1 shl 2), SQUEEZE(1 shl 3),
        MENU(1 shl 4), STICK_CLICK(1 shl 5), SYSTEM(1 shl 6)
    }

    data class State(
        val left: Hand = Hand(),
        val right: Hand = Hand(),
        /** Включено ли в настройках отслеживание положения по камере. */
        val sixDof: Boolean = true,
        /** Режим «только руки»: жесты пальцев ничего не нажимают. */
        val handsOnly: Boolean = false
    )

    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        soTimeout = 500
        bind(InetSocketAddress("127.0.0.1", port))
    }
    private val buffer = ByteArray(512)

    /** Ждёт следующий пакет. Возвращает null, если полсекунды данных не было. */
    fun read(): State? {
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            parse(String(packet.data, 0, packet.length, Charsets.US_ASCII))
        } catch (_: Throwable) {
            null
        }
    }

    override fun close() = socket.close()

    private fun parse(message: String): State? {
        val parts = message.trim().split(' ')
        if (parts.firstOrNull() != "PH4" || parts.size < 26) return null
        val values = parts.drop(1)
        fun hand(offset: Int) = Hand(
            present = values[offset].toInt() != 0,
            fist = values[offset + 1].toInt() != 0,
            index = values[offset + 2].toInt() != 0,
            thumb = values[offset + 3].toInt() != 0,
            x = values[offset + 4].toFloat(),
            y = values[offset + 5].toFloat(),
            z = values[offset + 6].toFloat(),
            qx = values[offset + 7].toFloat(),
            qy = values[offset + 8].toFloat(),
            qz = values[offset + 9].toFloat(),
            qw = values[offset + 10].toFloat(),
            buttons = values[offset + 11].toInt()
        )
        val flags = values[24].toInt()
        return State(hand(0), hand(12), flags and 1 != 0, flags and 2 != 0)
    }
}
