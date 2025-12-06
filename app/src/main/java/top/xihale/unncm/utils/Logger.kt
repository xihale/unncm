package top.xihale.unncm.utils

import android.util.Log

/**
 * 基于Android自带Log系统的简单日志工具类
 * 提供统一的日志输出格式和TAG管理
 */
object Logger {

    /**
     * VERBOSE 级别日志
     */
    fun v(tag: String, message: String) {
        Log.v(tag, message)
    }

    /**
     * DEBUG 级别日志
     */
    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    /**
     * INFO 级别日志
     */
    fun i(tag: String, message: String) {
        Log.i(tag, message)
    }

    /**
     * WARN 级别日志（带异常）
     */
    fun w(tag: String, message: String, throwable: Throwable) {
        Log.w(tag, message, throwable)
    }

    /**
     * WARN 级别日志（无异常）
     */
    fun w(tag: String, message: String) {
        Log.w(tag, message)
    }

    /**
     * ERROR 级别日志（带异常）
     */
    fun e(tag: String, message: String, throwable: Throwable) {
        Log.e(tag, message, throwable)
    }

    /**
     * ERROR 级别日志（无异常）
     */
    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    /**
     * 创建带固定TAG的日志记录器
     */
    fun withTag(tag: String): TaggedLogger {
        return TaggedLogger(tag)
    }

    /**
     * 带TAG的日志记录器
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