package top.xihale.unncm.utils

import android.util.Log

/**
 * Simple logging utility class based on Android's built-in Log system
 * Provides unified log output format and TAG management
 */
object Logger {

    /**
     * VERBOSE level logging
     */
    fun v(tag: String, message: String) {
        Log.v(tag, message)
    }

    /**
     * DEBUG level logging
     */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    /**
     * INFO level logging
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    /**
     * WARN level logging (with exception)
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        Log.w(tag, message, throwable)
    }

    /**
     * WARN level logging (without exception)
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    /**
     * ERROR level logging (with exception)
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
    }

    /**
     * ERROR level logging (without exception)
     */
    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    /**
     * Create a logger with a fixed TAG
     */
    fun withTag(tag: String): TaggedLogger {
        return TaggedLogger(tag)
    }

    /**
     * Logger with TAG
     */
    class TaggedLogger(private val tag: String) {
        fun v(message: String) = Logger.v(tag, message)
        fun d(message: String) = Logger.d(tag, message)
        fun i(message: String) = Logger.i(tag, message)
        fun w(message: String) = Logger.w(tag, message)
        fun w(message: String, throwable: Throwable) = Logger.w(tag, message, throwable)
        fun e(message: String, throwable: Throwable) = Logger.e(tag, message, throwable)
        fun e(message: String) = Logger.e(tag, message)
    }
}