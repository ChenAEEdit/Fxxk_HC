package com.example.myapplication

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.PrintWriter
import java.io.PrintStream

class AntiScreenshotModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 输出注入成功日志到 Xposed 日志中
        de.robv.android.xposed.XposedBridge.log("AntiScreenshotModule 成功注入目标: " + lpparam.packageName)

        // --- 1. 深度劫持配置源头 (粘贴/录屏配置) ---
        try {
            XposedHelpers.findAndHookMethod(JSONObject::class.java, "put", String::class.java, Any::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as? String ?: return
                    if (key == "pasteEnable" || key == "screenCaptureEnable") {
                        // 强制允许
                        if (param.args[1] is Int) param.args[1] = 1
                        else if (param.args[1] is Boolean) param.args[1] = true
                        else if (param.args[1] is String) param.args[1] = "1"
                    }
                }
            })
        } catch (_: Throwable) {}

        // --- 2. 劫持 Uni-app JS 桥接器 (拦截发送给前端的配置) ---
        try {
            val jsUtilClass = XposedHelpers.findClass("io.dcloud.common.util.JSUtil", lpparam.classLoader)
            val jsHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    for (i in param.args.indices) {
                        if (param.args[i] is String) {
                            var jsCode = param.args[i] as String
                            if (jsCode.contains("pasteEnable") || jsCode.contains("screenCaptureEnable")) {
                                jsCode = jsCode.replace("\"pasteEnable\":0", "\"pasteEnable\":1")
                                               .replace("\"pasteEnable\":false", "\"pasteEnable\":true")
                                               .replace("pasteEnable:0", "pasteEnable:1")
                                               .replace("\"screenCaptureEnable\":0", "\"screenCaptureEnable\":1")
                                param.args[i] = jsCode
                            }
                        }
                    }
                }
            }
            arrayOf("broadcastJS", "evaluateInJS", "execCallback").forEach { methodName ->
                try {
                    val methods = jsUtilClass.declaredMethods.filter { it.name == methodName }
                    methods.forEach { method ->
                        XposedHelpers.findAndHookMethod(jsUtilClass, methodName, *method.parameterTypes, jsHook)
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // --- 3. 增强版无障碍与悬浮窗绕过 ---
        try {
            val targetClass = "io.dcloud.uniplugin.FloatingWindowAccessibilityService"
            val returnTrue = object : XC_MethodHook() { override fun beforeHookedMethod(param: MethodHookParam) { param.result = true } }
            
            XposedHelpers.findAndHookMethod(targetClass, lpparam.classLoader, "isAccessibilityServiceEnabled", android.content.Context::class.java, returnTrue)
            XposedHelpers.findAndHookMethod(targetClass, lpparam.classLoader, "checkSettingsEnabled", android.content.Context::class.java, returnTrue)
            XposedHelpers.findAndHookMethod(targetClass, lpparam.classLoader, "isServiceRunning", returnTrue)
            
            XposedHelpers.findAndHookMethod(targetClass, lpparam.classLoader, "getForeignFloatingWindows", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = ArrayList<String>() }
            })
        } catch (_: Throwable) {}

        // --- 4. 霸屏模式 (应用固定) 欺骗 ---
        XposedHelpers.findAndHookMethod(Activity::class.java, "startLockTask", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
        })
        XposedHelpers.findAndHookMethod(Activity::class.java, "stopLockTask", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
        })

        val activityManagerClass = "android.app.ActivityManager"
        XposedHelpers.findAndHookMethod(activityManagerClass, lpparam.classLoader, "isInLockTaskMode", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = true }
        })
        XposedHelpers.findAndHookMethod(activityManagerClass, lpparam.classLoader, "getLockTaskModeState", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.result = 1 } // LOCK_TASK_MODE_LOCKED
        })

        // --- 5. 基础安全绕过 (截屏/焦点) ---
        XposedHelpers.findAndHookMethod(Window::class.java, "setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val mask = param.args[1] as Int
                if (mask and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                    param.args[0] = (param.args[0] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
                }
            }
        })

        XposedHelpers.findAndHookMethod(Activity::class.java, "onWindowFocusChanged", Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) { param.args[0] = true }
        })

        // --- 6. 静默拦截 (隐藏注入痕迹与作弊日志) ---
        try {
            val logClass = XposedHelpers.findClass("android.util.Log", lpparam.classLoader)
            val silenceHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val msg = param.args[1]?.toString()?.lowercase() ?: ""
                    val sensitiveKeywords = arrayOf("cheat", "report", "illegal", "paste", "screenshot", "record", "check", "xposed")
                    if (sensitiveKeywords.any { msg.contains(it) }) {
                        param.result = 0 
                    }
                }
            }
            arrayOf("v", "d", "i", "w", "e").forEach { m ->
                try {
                    XposedHelpers.findAndHookMethod(logClass, m, String::class.java, String::class.java, silenceHook)
                    XposedHelpers.findAndHookMethod(logClass, m, String::class.java, String::class.java, Throwable::class.java, silenceHook)
                } catch (_: Throwable) {}
            }
            
            // 彻底禁止异常堆栈打印到 Logcat
            XposedHelpers.findAndHookMethod(Throwable::class.java, "printStackTrace", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
            })
            XposedHelpers.findAndHookMethod(Throwable::class.java, "printStackTrace", PrintStream::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
            })
            XposedHelpers.findAndHookMethod(Throwable::class.java, "printStackTrace", PrintWriter::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) { param.result = null }
            })
        } catch (_: Throwable) {}
    }
}
