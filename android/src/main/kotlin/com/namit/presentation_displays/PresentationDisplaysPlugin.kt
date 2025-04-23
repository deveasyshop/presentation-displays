package com.namit.presentation_displays

import android.content.ContentValues.TAG
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.annotation.NonNull
import com.google.gson.Gson
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

private const val viewTypeId = "presentation_displays_plugin"
private const val viewTypeEventsId = "presentation_displays_plugin_events"

/** PresentationDisplaysPlugin */
class PresentationDisplaysPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {

  private lateinit var channel: MethodChannel
  private lateinit var eventChannel: EventChannel
  private var flutterEngineChannel: MethodChannel? = null
  private var context: Context? = null
  private var presentation: PresentationDisplay? = null
  private var displayManager: DisplayManager? = null

  override fun onAttachedToEngine(
    @NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
  ) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, viewTypeId)
    channel.setMethodCallHandler(this)

    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, viewTypeEventsId)
    displayManager =
      flutterPluginBinding.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val displayConnectedStreamHandler = DisplayConnectedStreamHandler(displayManager)
    eventChannel.setStreamHandler(displayConnectedStreamHandler)
  }

  override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    this.context = binding.activity
    displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
  }

  override fun onDetachedFromActivity() {}

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    onAttachedToActivity(binding)
  }

  override fun onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity()
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    Log.i(TAG, "Channel: method: ${call.method} | arguments: ${call.arguments}")
    when (call.method) {
      "showPresentation" -> {
        try {
          val obj = JSONObject(call.arguments as String)
          val displayId: Int = obj.getInt("displayId")
          val tag: String = obj.getString("routerName")
          val display = displayManager?.getDisplay(displayId)
          if (display != null) {
            val flutterEngine = createFlutterEngine(tag)
            flutterEngine?.let {
              flutterEngineChannel =
                MethodChannel(it.dartExecutor.binaryMessenger, "${viewTypeId}_engine")
              presentation = context?.let { ctx -> PresentationDisplay(ctx, tag, display) }
              presentation?.show()
              result.success(true)
            } ?: result.error("404", "Can't find FlutterEngine", null)
          } else {
            result.error("404", "Can't find display with displayId $displayId", null)
          }
        } catch (e: Exception) {
          result.error(call.method, e.message, null)
        }
      }
      "hidePresentation" -> {
        try {
          presentation?.dismiss()
          presentation = null
          result.success(true)
        } catch (e: Exception) {
          result.error(call.method, e.message, null)
        }
      }
      "listDisplay" -> {
        val listJson = ArrayList<DisplayJson>()
        val category = call.arguments
        val displays = displayManager?.getDisplays(category as String?)
        displays?.forEach {
          listJson.add(DisplayJson(it.displayId, it.flags, it.rotation, it.name))
        }
        result.success(Gson().toJson(listJson))
      }
      "transferDataToPresentation" -> {
        try {
          flutterEngineChannel?.invokeMethod("DataTransfer", call.arguments)
          result.success(true)
        } catch (e: Exception) {
          result.success(false)
        }
      }
      else -> result.notImplemented()
    }
  }

  private fun createFlutterEngine(tag: String): FlutterEngine? {
    if (context == null) return null
    if (FlutterEngineCache.getInstance().get(tag) == null) {
      val flutterEngine = FlutterEngine(context!!)
      flutterEngine.navigationChannel.setInitialRoute(tag)
      FlutterInjector.instance().flutterLoader().startInitialization(context!!)
      val path = FlutterInjector.instance().flutterLoader().findAppBundlePath()
      val entrypoint = DartExecutor.DartEntrypoint(path, "secondaryDisplayMain")
      flutterEngine.dartExecutor.executeDartEntrypoint(entrypoint)
      flutterEngine.lifecycleChannel.appIsResumed()
      FlutterEngineCache.getInstance().put(tag, flutterEngine)
    }
    return FlutterEngineCache.getInstance().get(tag)
  }
}

class DisplayConnectedStreamHandler(private var displayManager: DisplayManager?) :
  EventChannel.StreamHandler {
  private var sink: EventChannel.EventSink? = null
  private var handler: Handler? = null

  private val displayListener =
    object : DisplayManager.DisplayListener {
      override fun onDisplayAdded(displayId: Int) {
        sink?.success(1)
      }

      override fun onDisplayRemoved(displayId: Int) {
        sink?.success(0)
      }

      override fun onDisplayChanged(p0: Int) {}
    }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    sink = events
    handler = Handler(Looper.getMainLooper())
    displayManager?.registerDisplayListener(displayListener, handler)
  }

  override fun onCancel(arguments: Any?) {
    sink = null
    handler = null
    displayManager?.unregisterDisplayListener(displayListener)
  }
}
