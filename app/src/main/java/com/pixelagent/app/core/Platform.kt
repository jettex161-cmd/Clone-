package com.pixelagent.app.core

/** Base64-encoded, size-reduced image ready to send to a vision-capable model. */
class EncodedImage(val mime: String, val base64: String)

/** Operates the phone's screen (implemented by the accessibility service). All calls block briefly. */
interface ScreenController {
    fun read(): String
    fun clickById(id: Int): String
    fun clickByText(text: String): String
    fun tap(x: Int, y: Int): String
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String
    fun scroll(direction: String): String
    fun typeText(text: String, id: Int?): String
    fun pressKey(key: String): String
}

/** Everything the agent needs from the Android OS. Implemented in the platform package. */
interface Platform {
    fun deviceInfo(): String
    fun listApps(query: String): String
    fun openApp(app: String): String
    fun openUrl(url: String): String
    fun setAlarm(hour: Int, minute: Int, label: String): String
    fun setTimer(seconds: Int, label: String): String
    fun clipboardGet(): String
    fun clipboardSet(text: String): String
    fun notify(title: String, text: String): String
    fun imageInfo(path: String): String
    fun encodeImage(path: String): EncodedImage?

    /** Null when phone control (accessibility service) is not enabled. */
    fun screen(): ScreenController?
}
