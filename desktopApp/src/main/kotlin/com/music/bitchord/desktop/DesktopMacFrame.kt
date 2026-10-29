package com.music.bitchord.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.awt.Color
import java.awt.Toolkit
import java.awt.Window
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.RootPaneContainer

/**
 * macOS native window frame and vibrancy bridge via AppKit and Objective-C runtime (JNA).
 * Attaches an NSVisualEffectView to provide authentic macOS frosted glass materials
 * behind the window chrome.
 */
internal object DesktopMacFrame {

    private val installed = AtomicBoolean(false)
    private var windowPtr: Pointer? = null
    private var awtWindow: Window? = null
    private var effectViewPtr: Pointer? = null
    @Volatile
    private var currentBackdropKind: Int = 0
    private var fullscreenObserversRegistered = false
    private var eawtListenerRegistered = false
    private var eawtListenerRef: Any? = null
    private val fieldEditorStubInstalled = AtomicBoolean(false)
    /** Target of an in-flight Space transition; null when AppKit has settled. */
    @Volatile
    private var fullscreenTransitionTarget: Boolean? = null
    /** Electron-style queue: one pending desire while a transition is already running. */
    @Volatile
    private var pendingFullscreenDesire: Boolean? = null
    /** Dedupes eAWT + NSNotification both reporting the same enter/exit. */
    @Volatile
    private var lastFullscreenUiState: Boolean? = null
    private var pendingExitRepair: javax.swing.Timer? = null
    private var darkAppearanceApplied = false
    /** Style mask before we forced Resizable for a Space enter; restored on exit. */
    @Volatile
    private var styleMaskBeforeFullscreen: Long? = null

    private interface LibObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_allocateClassPair(superclass: Pointer, name: String, extraBytes: Long): Pointer?
        fun objc_registerClassPair(cls: Pointer)
        fun class_addMethod(cls: Pointer, name: Pointer, imp: Callback, types: String): Boolean
    }

    private interface ActionCallback : Callback {
        fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer)
    }

    /** Returns nil so AppKit uses the default field editor. */
    private interface FieldEditorCallback : Callback {
        fun invoke(self: Pointer, _cmd: Pointer, sender: Pointer?, client: Pointer?): Pointer?
    }

    /** NSWindowStyleMaskResizable — AppKit refuses to expand into a Space without this bit
     * (OpenJFX JDK-8087498 / JDK-8353314, Compose #4573 for undecorated windows). */
    private const val NS_WINDOW_STYLE_MASK_RESIZABLE = 1L shl 3

    /** NSWindowStyleMaskFullScreen — set while the window occupies its own Space. */
    private const val NS_WINDOW_STYLE_MASK_FULL_SCREEN = 1L shl 14

    /** NSWindowCollectionBehaviorFullScreenPrimary — allows -[NSWindow toggleFullScreen:]. */
    private const val NS_WINDOW_COLLECTION_BEHAVIOR_FULL_SCREEN_PRIMARY = 1L shl 7

    private val objc: LibObjC? by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { Native.load("objc", LibObjC::class.java) }.getOrNull()
    }

    private val msgSend by lazy {
        if (!DesktopPlatform.isMac) null
        else runCatching { NativeLibrary.getInstance("objc").getFunction("objc_msgSend") }.getOrNull()
    }

    // Keep strong references to callbacks so they are not garbage collected by JNA
    private val callbackRef = object : ActionCallback {
        override fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer) {
            runCatching {
                applyNativeBackdropOnMainThread(currentBackdropKind)
            }.onFailure {
                DesktopTrackLog.log("DesktopMacFrame main thread error: ${it.message}")
            }
        }
    }

    private val activateCallbackRef = object : ActionCallback {
        override fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer) {
            runCatching {
                activateAppOnMainThread()
            }.onFailure {
                DesktopTrackLog.log("DesktopMacFrame activate main thread error: ${it.message}")
            }
        }
    }

    private val toggleFullScreenCallbackRef = object : ActionCallback {
        override fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer) {
            runCatching {
                toggleFullScreenOnMainThread()
            }.onFailure {
                fullscreenTransitionTarget = null
                DesktopTrackLog.log("DesktopMacFrame toggleFullScreen main thread error: ${it.message}")
            }
        }
    }

    private val ensureResizableCallbackRef = object : ActionCallback {
        override fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer) {
            runCatching {
                windowPtr?.let { ensureResizableStyleMaskOnMainThread(it) }
            }.onFailure {
                DesktopTrackLog.log("DesktopMacFrame ensureResizable main thread error: ${it.message}")
            }
        }
    }

    private val restoreStyleMaskCallbackRef = object : ActionCallback {
        override fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer) {
            runCatching {
                windowPtr?.let { restoreStyleMaskAfterFullscreenOnMainThread(it) }
            }.onFailure {
                DesktopTrackLog.log("DesktopMacFrame restoreStyleMask main thread error: ${it.message}")
            }
        }
    }

    private val didEnterFullScreenRef = object : ActionCallback {
        override fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer) {
            onNativeFullscreenChanged(entered = true)
            DesktopTrackLog.log("DesktopMacFrame: entered full-screen Space")
        }
    }

    private val didExitFullScreenRef = object : ActionCallback {
        override fun invoke(self: Pointer, _cmd: Pointer, arg: Pointer) {
            onNativeFullscreenChanged(entered = false)
            DesktopTrackLog.log("DesktopMacFrame: left full-screen Space")
        }
    }

    /**
     * Newer AppKit calls this on the window delegate during setStyleMask: (fullscreen enter)
     * without a respondsToSelector: guard. AWTWindow does not implement it, which aborts the
     * process. Returning nil keeps the default field editor.
     */
    private val fieldEditorStubRef = object : FieldEditorCallback {
        override fun invoke(
            self: Pointer,
            _cmd: Pointer,
            sender: Pointer?,
            client: Pointer?,
        ): Pointer? = null
    }

    private val helperInstance: Pointer? by lazy {
        initHelper()
    }

    private val selPerformOnMain: Pointer? by lazy {
        objc?.sel_registerName("performOnMainThread:on:withObject:waitUntilDone:")
    }

    private val selApplyBackdrop: Pointer? by lazy {
        objc?.sel_registerName("applyBackdrop:")
    }

    private val selActivateApp: Pointer? by lazy {
        objc?.sel_registerName("activateApp:")
    }

    private val selToggleFullScreenAction: Pointer? by lazy {
        objc?.sel_registerName("toggleFullScreenAction:")
    }

    private val selEnsureResizableAction: Pointer? by lazy {
        objc?.sel_registerName("ensureResizableAction:")
    }

    private val selRestoreStyleMaskAction: Pointer? by lazy {
        objc?.sel_registerName("restoreStyleMaskAction:")
    }

    private val threadUtilities: Pointer? by lazy {
        Toolkit.getDefaultToolkit() // Ensure AWT native libs are loaded
        objc?.objc_getClass("ThreadUtilities")
    }

    private fun initHelper(): Pointer? {
        val o = objc ?: return null
        val send = msgSend ?: return null
        val nsObjectClass = o.objc_getClass("NSObject") ?: return null
        val clsName = "BitChordMacFrameHelper"
        var cls = o.objc_getClass(clsName)
        val created = cls == null
        if (cls == null) {
            cls = o.objc_allocateClassPair(nsObjectClass, clsName, 0L) ?: return null
        }
        // Add every selector we need. class_addMethod is a no-op when the method already exists,
        // so a helper left over from an earlier run in the same JVM still picks up new ones.
        o.class_addMethod(cls, o.sel_registerName("applyBackdrop:"), callbackRef, "v@:@")
        o.class_addMethod(cls, o.sel_registerName("activateApp:"), activateCallbackRef, "v@:@")
        o.class_addMethod(cls, o.sel_registerName("toggleFullScreenAction:"), toggleFullScreenCallbackRef, "v@:@")
        o.class_addMethod(cls, o.sel_registerName("ensureResizableAction:"), ensureResizableCallbackRef, "v@:@")
        o.class_addMethod(cls, o.sel_registerName("restoreStyleMaskAction:"), restoreStyleMaskCallbackRef, "v@:@")
        o.class_addMethod(cls, o.sel_registerName("didEnterFullScreen:"), didEnterFullScreenRef, "v@:@")
        o.class_addMethod(cls, o.sel_registerName("didExitFullScreen:"), didExitFullScreenRef, "v@:@")
        if (created) o.objc_registerClassPair(cls)
        val selAlloc = o.sel_registerName("alloc")
        val selInit = o.sel_registerName("init")
        val alloc = send.invokePointer(arrayOf(cls, selAlloc)) ?: return null
        return send.invokePointer(arrayOf(alloc, selInit))
    }

    private fun isMainThread(): Boolean {
        val o = objc ?: return false
        val send = msgSend ?: return false
        val nsThreadClass = o.objc_getClass("NSThread") ?: return false
        val selIsMainThread = o.sel_registerName("isMainThread")
        return runCatching {
            send.invoke(Boolean::class.java, arrayOf(nsThreadClass, selIsMainThread)) as? Boolean
        }.getOrDefault(false) ?: false
    }

    private fun dispatchToMainThread() {
        val tu = threadUtilities ?: return
        val helper = helperInstance ?: return
        val selPerform = selPerformOnMain ?: return
        val selAction = selApplyBackdrop ?: return
        val send = msgSend ?: return
        send.invoke(arrayOf(tu, selPerform, selAction, helper, helper, false))
    }

    private fun dispatchActivateToMainThread() {
        val tu = threadUtilities ?: run {
            activateAppOnMainThread()
            return
        }
        val helper = helperInstance ?: run {
            activateAppOnMainThread()
            return
        }
        val selPerform = selPerformOnMain ?: run {
            activateAppOnMainThread()
            return
        }
        val selAction = selActivateApp ?: run {
            activateAppOnMainThread()
            return
        }
        val send = msgSend ?: run {
            activateAppOnMainThread()
            return
        }
        send.invoke(arrayOf(tu, selPerform, selAction, helper, helper, false))
    }

    internal fun isObjcAvailable(): Boolean {
        val o = objc ?: return false
        val nsWindowClass = o.objc_getClass("NSWindow")
        val nsEffectClass = o.objc_getClass("NSVisualEffectView")
        return nsWindowClass != null && nsEffectClass != null
    }

    private fun isNSWindow(ptr: Pointer?): Boolean {
        if (ptr == null || ptr == Pointer.NULL) return false
        val o = objc ?: return false
        val send = msgSend ?: return false
        val nsWindowClass = o.objc_getClass("NSWindow") ?: return false
        val selIsKindOfClass = o.sel_registerName("isKindOfClass:")
        return runCatching {
            send.invoke(Boolean::class.java, arrayOf(ptr, selIsKindOfClass, nsWindowClass)) as? Boolean
        }.getOrDefault(false) ?: false
    }

    private fun toNSWindow(handle: Pointer): Pointer {
        if (isNSWindow(handle)) return handle
        val o = objc ?: return handle
        val send = msgSend ?: return handle

        val selNsWindow = o.sel_registerName("nsWindow")
        val winFromNsWindow = runCatching {
            send.invokePointer(arrayOf(handle, selNsWindow))
        }.getOrNull()
        if (winFromNsWindow != null && isNSWindow(winFromNsWindow)) return winFromNsWindow

        val selWindow = o.sel_registerName("window")
        val winFromWindow = runCatching {
            send.invokePointer(arrayOf(handle, selWindow))
        }.getOrNull()
        if (winFromWindow != null && isNSWindow(winFromWindow)) return winFromWindow

        return handle
    }

    private fun findAppKitWindow(): Pointer? {
        val o = objc ?: return null
        val send = msgSend ?: return null
        val nsAppClass = o.objc_getClass("NSApplication") ?: return null
        val selSharedApp = o.sel_registerName("sharedApplication")
        val app = send.invokePointer(arrayOf(nsAppClass, selSharedApp)) ?: return null

        val selKeyWindow = o.sel_registerName("keyWindow")
        val keyWin = send.invokePointer(arrayOf(app, selKeyWindow))
        if (keyWin != null && isNSWindow(keyWin)) return keyWin

        val selMainWindow = o.sel_registerName("mainWindow")
        val mainWin = send.invokePointer(arrayOf(app, selMainWindow))
        if (mainWin != null && isNSWindow(mainWin)) return mainWin

        val selWindows = o.sel_registerName("windows")
        val windows = send.invokePointer(arrayOf(app, selWindows)) ?: return null
        val selCount = o.sel_registerName("count")
        val count = (send.invoke(Long::class.java, arrayOf(windows, selCount)) as? Number)?.toLong() ?: 0L
        val selObjAtIndex = o.sel_registerName("objectAtIndex:")
        for (i in 0 until count) {
            val win = send.invokePointer(arrayOf(windows, selObjAtIndex, i)) ?: continue
            if (isNSWindow(win)) return win
        }
        return null
    }

    private fun getWindowPointer(window: Window): Pointer? {
        try {
            val method = window.javaClass.getMethod("getWindowHandle")
            val handle = method.invoke(window) as? Long
            if (handle != null && handle != 0L) {
                return Pointer.createConstant(handle)
            }
        } catch (_: Throwable) {}

        // Fallback: search AppKit NSApplication windows
        findAppKitWindow()?.let { return it }

        // Fallback: AWT peer reflection
        return try {
            val peer = window.javaClass.getMethod("getPeer").invoke(window)
            val platformWindow = peer.javaClass.getMethod("getPlatformWindow").invoke(peer)
            var targetClass: Class<*>? = platformWindow.javaClass
            var field: java.lang.reflect.Field? = null
            while (targetClass != null && field == null) {
                field = runCatching { targetClass.getDeclaredField("ptr") }.getOrNull()
                targetClass = targetClass.superclass
            }
            if (field != null) {
                field.isAccessible = true
                val ptr = field.getLong(platformWindow)
                if (ptr != 0L) Pointer.createConstant(ptr) else null
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    /** Prepares the window for macOS transparency and vibrancy. */
    fun install(window: Window): Boolean {
        if (!DesktopPlatform.isMac) return false

        // Clear AWT background layers immediately on the caller thread
        window.background = Color(0, 0, 0, 0)
        (window as? javax.swing.RootPaneContainer)?.let { rpc ->
            rpc.contentPane?.background = Color(0, 0, 0, 0)
            (rpc.contentPane as? javax.swing.JComponent)?.isOpaque = false
            rpc.rootPane?.isOpaque = false
            rpc.rootPane?.background = Color(0, 0, 0, 0)
            rpc.rootPane?.putClientProperty("apple.awt.transparentTitleBar", true)
            rpc.rootPane?.putClientProperty("apple.awt.fullWindowContent", true)
        }

        val handle = getWindowPointer(window) ?: run {
            DesktopTrackLog.log("DesktopMacFrame: failed to obtain window pointer")
            return false
        }
        val nsWindow = toNSWindow(handle)
        if (!isNSWindow(nsWindow)) {
            DesktopTrackLog.log("DesktopMacFrame: pointer is not an NSWindow: $nsWindow")
            return false
        }
        windowPtr = nsWindow
        awtWindow = window
        installed.set(true)

        ensureAwtWindowFieldEditorStub()
        enableEawtFullScreen(window)
        if (isMainThread()) {
            runCatching {
                ensureResizableStyleMask(nsWindow)
                enableFullScreenPrimary(nsWindow)
                registerFullscreenObservers(nsWindow)
                applyNativeBackdropOnMainThread(currentBackdropKind)
            }
        } else {
            dispatchToMainThread()
        }
        DesktopTrackLog.log("DesktopMacFrame: installed successfully on NSWindow $nsWindow")
        return true
    }

    /**
     * Prep for Compose [WindowPlacement.Fullscreen] / Skiko's native Space toggle:
     * field-editor stub, eAWT flags, FullScreenPrimary, and Resizable style bit.
     * Call before flipping placement — do not toggle AppKit yourself or Compose will fight it.
     */
    fun prepareNativeFullscreen(): Boolean {
        if (!DesktopPlatform.isMac || !installed.get()) return false
        val window = awtWindow ?: return false
        ensureAwtWindowFieldEditorStub()
        enableEawtFullScreen(window)
        windowPtr?.let { ns ->
            ensureResizableStyleMask(ns)
            enableFullScreenPrimary(ns)
            registerFullscreenObservers(ns)
        }
        return true
    }

    /**
     * Direct AppKit toggle (green-button / system paths that bypass Compose placement).
     * Prefer [prepareNativeFullscreen] + [WindowPlacement.Fullscreen] for in-app toggles.
     */
    fun setFullscreen(on: Boolean): Boolean {
        if (!DesktopPlatform.isMac || !installed.get()) return false
        val window = awtWindow ?: return false
        if (isFullscreen() == on && fullscreenTransitionTarget == null) {
            pendingFullscreenDesire = null
            return true
        }
        if (fullscreenTransitionTarget != null) {
            pendingFullscreenDesire = on
            DesktopTrackLog.log("DesktopMacFrame: queued fullscreen desire=$on")
            return true
        }
        prepareNativeFullscreen()
        fullscreenTransitionTarget = on
        return requestToggleFullScreen(window).also { ok ->
            if (ok) DesktopTrackLog.log("DesktopMacFrame: requestToggleFullScreen toward $on")
            else {
                fullscreenTransitionTarget = null
                DesktopTrackLog.log("DesktopMacFrame: requestToggleFullScreen failed")
            }
        }
    }

    /** Whether the NSWindow currently occupies a full-screen Space. */
    fun isFullscreen(): Boolean {
        if (!DesktopPlatform.isMac) return false
        val o = objc ?: return false
        val send = msgSend ?: return false
        val nsWindow = windowPtr ?: return false
        return runCatching {
            val selStyleMask = o.sel_registerName("styleMask")
            val mask = (send.invoke(Long::class.java, arrayOf(nsWindow, selStyleMask)) as? Number)?.toLong() ?: 0L
            mask and NS_WINDOW_STYLE_MASK_FULL_SCREEN != 0L
        }.getOrDefault(false)
    }

    /**
     * What the next toggle should invert — pending desire, in-flight target, or settled state.
     * Matches Electron not flipping `isFullScreen()` until the Space animation finishes.
     */
    fun desiredFullscreen(): Boolean =
        pendingFullscreenDesire
            ?: fullscreenTransitionTarget
            ?: lastFullscreenUiState
            ?: isFullscreen()

    /** Applies or removes the NSVisualEffectView native backdrop blur. */
    fun setBackdrop(nativeKind: Int): Boolean {
        if (!DesktopPlatform.isMac) return false
        currentBackdropKind = nativeKind
        if (isMainThread()) {
            runCatching {
                applyNativeBackdropOnMainThread(nativeKind)
            }.onFailure {
                DesktopTrackLog.log("DesktopMacFrame error: ${it.message}")
            }
        } else {
            dispatchToMainThread()
        }
        return true
    }

    /** Adjusts the vibrancy view corner radius to match the Compose window placement. */
    fun updateCornerRadius(maximized: Boolean) {
        if (!DesktopPlatform.isMac) return
        val o = objc ?: return
        val send = msgSend ?: return
        val view = effectViewPtr ?: return
        val selLayer = o.sel_registerName("layer")
        val layer = send.invokePointer(arrayOf(view, selLayer)) ?: return
        val selSetCornerRadius = o.sel_registerName("setCornerRadius:")
        send.invoke(arrayOf(layer, selSetCornerRadius, if (maximized) 0.0 else 10.0))
    }

    /**
     * Hands window dragging to AppKit via -[NSWindow performWindowDragWithEvent:],
     * providing native macOS ProMotion dragging, Spaces switching, and Mission Control support.
     */
    fun startDrag(): Boolean {
        if (!DesktopPlatform.isMac) return false
        val o = objc ?: return false
        val send = msgSend ?: return false
        val nsWindow = windowPtr ?: return false

        return runCatching {
            val nsAppClass = o.objc_getClass("NSApplication") ?: return false
            val selSharedApp = o.sel_registerName("sharedApplication")
            val app = send.invokePointer(arrayOf(nsAppClass, selSharedApp)) ?: return false
            val selCurrentEvent = o.sel_registerName("currentEvent")
            val event = send.invokePointer(arrayOf(app, selCurrentEvent))
            if (event == null || event == Pointer.NULL) return false

            val selPerformDrag = o.sel_registerName("performWindowDragWithEvent:")
            send.invoke(arrayOf(nsWindow, selPerformDrag, event))
            true
        }.getOrDefault(false)
    }

    /**
     * Activates the macOS application process and restores/orders the window to the front.
     * Called when the app is reopened from the macOS Dock, Spotlight, or system tray.
     */
    fun activateApp() {
        if (!DesktopPlatform.isMac) return
        if (isMainThread()) {
            runCatching { activateAppOnMainThread() }
        } else {
            dispatchActivateToMainThread()
        }
    }

    private fun activateAppOnMainThread() {
        val o = objc ?: return
        val send = msgSend ?: return
        val nsWindow = windowPtr ?: findAppKitWindow() ?: return

        runCatching {
            val nsAppClass = o.objc_getClass("NSApplication") ?: return
            val selSharedApp = o.sel_registerName("sharedApplication")
            val app = send.invokePointer(arrayOf(nsAppClass, selSharedApp)) ?: return

            // 1. Activate application (bring app process to foreground over other applications)
            val selActivate = o.sel_registerName("activateIgnoringOtherApps:")
            send.invoke(arrayOf(app, selActivate, true))

            // 2. Unminimize window if miniaturized
            val selIsMiniaturized = o.sel_registerName("isMiniaturized")
            val isMini = (send.invoke(Boolean::class.java, arrayOf(nsWindow, selIsMiniaturized)) as? Boolean) ?: false
            if (isMini) {
                val selDeminiaturize = o.sel_registerName("deminiaturize:")
                send.invoke(arrayOf(nsWindow, selDeminiaturize, Pointer.NULL))
            }

            // 3. Make key and order front
            val selMakeKeyAndOrderFront = o.sel_registerName("makeKeyAndOrderFront:")
            send.invoke(arrayOf(nsWindow, selMakeKeyAndOrderFront, Pointer.NULL))

            // 4. Invalidate window shadow so it redraws cleanly
            val selInvalidateShadow = o.sel_registerName("invalidateShadow")
            send.invoke(arrayOf(nsWindow, selInvalidateShadow))

            DesktopTrackLog.log("DesktopMacFrame: activateApp executed successfully on $nsWindow")
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame.activateApp failed: ${it.message}")
        }
    }

    private var windowConfigured = false

    /**
     * Space transitions are reported twice (eAWT + NSNotification). First report wins for UI.
     * Do not touch Compose size or AWT bounds here — AppKit owns the frame (Electron model).
     */
    private fun onNativeFullscreenChanged(entered: Boolean) {
        fullscreenTransitionTarget = null
        if (lastFullscreenUiState != entered) {
            lastFullscreenUiState = entered
            DesktopWindowMode.adoptNativeFullscreen(entered)
            updateCornerRadius(entered || DesktopWindowMode.maximized.value)
            DesktopTrackLog.log(
                "DesktopMacFrame: Space ${if (entered) "entered" else "exited"} " +
                    "awt=${awtWindow?.width}x${awtWindow?.height}",
            )
        }
        // Drain Electron-style pending desire after the animation settles.
        val queued = pendingFullscreenDesire
        pendingFullscreenDesire = null
        if (queued != null && queued != entered) {
            javax.swing.SwingUtilities.invokeLater { setFullscreen(queued) }
            return
        }
        pendingExitRepair?.stop()
        pendingExitRepair = null
        if (entered) return
        windowPtr?.let { restoreStyleMaskAfterFullscreen(it) }
        val timer = javax.swing.Timer(350) {
            pendingExitRepair = null
            repairBackdropAfterFullscreen()
        }
        timer.isRepeats = false
        pendingExitRepair = timer
        timer.start()
    }

    /**
     * Undecorated / transparent AWT windows often lack Resizable in their AppKit style mask.
     * Without it, [toggleFullScreen:] creates a Space but leaves the content at the old size
     * (Compose #4573 / OpenJFX JDK-8353314). [NSWindow setStyleMask:] must run on the AppKit
     * main thread — calling it from AWT-EventQueue traps with "Must only be used from the main thread".
     */
    private fun ensureResizableStyleMask(nsWindow: Pointer) {
        if (isMainThread()) {
            ensureResizableStyleMaskOnMainThread(nsWindow)
            return
        }
        val tu = threadUtilities ?: return
        val helper = helperInstance ?: return
        val selPerform = selPerformOnMain ?: return
        val selAction = selEnsureResizableAction ?: return
        val send = msgSend ?: return
        // waitUntilDone=true so Resizable is set before Skiko/Compose toggles fullscreen.
        runCatching {
            send.invoke(arrayOf(tu, selPerform, selAction, helper, helper, true))
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: dispatch ensureResizable failed: ${it.message}")
        }
    }

    private fun ensureResizableStyleMaskOnMainThread(nsWindow: Pointer) {
        val o = objc ?: return
        val send = msgSend ?: return
        runCatching {
            val selStyleMask = o.sel_registerName("styleMask")
            val current = (send.invoke(Long::class.java, arrayOf(nsWindow, selStyleMask)) as? Number)
                ?.toLong() ?: return
            if (current and NS_WINDOW_STYLE_MASK_RESIZABLE != 0L) return
            if (styleMaskBeforeFullscreen == null) styleMaskBeforeFullscreen = current
            val selSetStyleMask = o.sel_registerName("setStyleMask:")
            send.invoke(arrayOf(nsWindow, selSetStyleMask, current or NS_WINDOW_STYLE_MASK_RESIZABLE))
            DesktopTrackLog.log(
                "DesktopMacFrame: set NSWindowStyleMaskResizable for Space fullscreen " +
                    "(was 0x${current.toString(16)})",
            )
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: ensureResizableStyleMask failed: ${it.message}")
        }
    }

    private fun restoreStyleMaskAfterFullscreen(nsWindow: Pointer) {
        if (styleMaskBeforeFullscreen == null) return
        if (isMainThread()) {
            restoreStyleMaskAfterFullscreenOnMainThread(nsWindow)
            return
        }
        val tu = threadUtilities ?: return
        val helper = helperInstance ?: return
        val selPerform = selPerformOnMain ?: return
        val selAction = selRestoreStyleMaskAction ?: return
        val send = msgSend ?: return
        runCatching {
            send.invoke(arrayOf(tu, selPerform, selAction, helper, helper, false))
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: dispatch restoreStyleMask failed: ${it.message}")
        }
    }

    private fun restoreStyleMaskAfterFullscreenOnMainThread(nsWindow: Pointer) {
        val saved = styleMaskBeforeFullscreen ?: return
        styleMaskBeforeFullscreen = null
        val o = objc ?: return
        val send = msgSend ?: return
        runCatching {
            val selSetStyleMask = o.sel_registerName("setStyleMask:")
            send.invoke(arrayOf(nsWindow, selSetStyleMask, saved and NS_WINDOW_STYLE_MASK_FULL_SCREEN.inv()))
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: restoreStyleMask failed: ${it.message}")
        }
    }

    private fun repairBackdropAfterFullscreen() {
        val existing = effectViewPtr
        if (existing != null && isViewAttached(existing)) {
            // Hierarchy survived — just refresh material/corners, no teardown.
            updateCornerRadius(DesktopWindowMode.maximized.value)
            DesktopWindowBackdrop.apply()
            return
        }
        DesktopTrackLog.log("DesktopMacFrame: repairing vibrancy after full-screen exit")
        invalidateEffectView()
        windowConfigured = false
        awtWindow?.let { window ->
            window.background = Color(0, 0, 0, 0)
            (window as? RootPaneContainer)?.let { rpc ->
                rpc.contentPane?.background = Color(0, 0, 0, 0)
                (rpc.contentPane as? javax.swing.JComponent)?.isOpaque = false
                rpc.rootPane?.isOpaque = false
                rpc.rootPane?.background = Color(0, 0, 0, 0)
            }
        }
        DesktopWindowBackdrop.apply()
    }

    private fun isViewAttached(view: Pointer): Boolean {
        val o = objc ?: return false
        val send = msgSend ?: return false
        return runCatching {
            val selSuperview = o.sel_registerName("superview")
            val superview = send.invokePointer(arrayOf(view, selSuperview))
            superview != null && superview != Pointer.NULL
        }.getOrDefault(false)
    }

    private fun invalidateEffectView() {
        val o = objc
        val send = msgSend
        val view = effectViewPtr
        effectViewPtr = null
        if (o == null || send == null || view == null) return
        runCatching {
            val selRemove = o.sel_registerName("removeFromSuperview")
            send.invoke(arrayOf(view, selRemove))
        }
    }

    private fun applyDarkAppearance(nsWindow: Pointer, effectView: Pointer?) {
        val o = objc ?: return
        val send = msgSend ?: return
        runCatching {
            val nsAppearanceClass = o.objc_getClass("NSAppearance") ?: return
            val nsStringClass = o.objc_getClass("NSString") ?: return
            val selStringWithUtf8 = o.sel_registerName("stringWithUTF8String:")
            val selAppearanceNamed = o.sel_registerName("appearanceNamed:")
            val darkAquaStr = send.invokePointer(arrayOf(nsStringClass, selStringWithUtf8, "NSAppearanceNameDarkAqua")) ?: return
            val darkAppearance = send.invokePointer(arrayOf(nsAppearanceClass, selAppearanceNamed, darkAquaStr)) ?: return

            val selSetAppearance = o.sel_registerName("setAppearance:")

            // App-wide appearance once; repeated setAppearance: during Space transitions stalls UI.
            if (!darkAppearanceApplied) {
                val nsAppClass = o.objc_getClass("NSApplication")
                if (nsAppClass != null) {
                    val selSharedApp = o.sel_registerName("sharedApplication")
                    val app = send.invokePointer(arrayOf(nsAppClass, selSharedApp))
                    if (app != null && app != Pointer.NULL) {
                        send.invoke(arrayOf(app, selSetAppearance, darkAppearance))
                    }
                }
                darkAppearanceApplied = true
            }

            send.invoke(arrayOf(nsWindow, selSetAppearance, darkAppearance))
            if (effectView != null && effectView != Pointer.NULL) {
                send.invoke(arrayOf(effectView, selSetAppearance, darkAppearance))
            }
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: failed to apply dark appearance: ${it.message}")
        }
    }

    private fun ensureWindowAndEffectView(o: LibObjC, send: com.sun.jna.Function, nsWindow: Pointer): Pointer? {
        if (!windowConfigured) {
            val selSetOpaque = o.sel_registerName("setOpaque:")
            send.invoke(arrayOf(nsWindow, selSetOpaque, false))

            val nsColorClass = o.objc_getClass("NSColor")
            if (nsColorClass != null) {
                val selClearColor = o.sel_registerName("clearColor")
                val clearColor = send.invokePointer(arrayOf(nsColorClass, selClearColor))
                if (clearColor != null) {
                    val selSetBackgroundColor = o.sel_registerName("setBackgroundColor:")
                    send.invoke(arrayOf(nsWindow, selSetBackgroundColor, clearColor))
                }
            }

            val selSetHasShadow = o.sel_registerName("setHasShadow:")
            send.invoke(arrayOf(nsWindow, selSetHasShadow, true))

            val selInvalidateShadow = o.sel_registerName("invalidateShadow")
            send.invoke(arrayOf(nsWindow, selInvalidateShadow))

            windowConfigured = true
        }

        effectViewPtr?.let { existing ->
            if (isViewAttached(existing)) return existing
            DesktopTrackLog.log("DesktopMacFrame: effect view detached — recreating")
            invalidateEffectView()
        }

        val selContentView = o.sel_registerName("contentView")
        val contentView = send.invokePointer(arrayOf(nsWindow, selContentView)) ?: run {
            DesktopTrackLog.log("DesktopMacFrame: contentView is null")
            return null
        }

        val selSuperview = o.sel_registerName("superview")
        val superview = send.invokePointer(arrayOf(contentView, selSuperview)) ?: contentView

        val effectClass = o.objc_getClass("NSVisualEffectView") ?: run {
            DesktopTrackLog.log("DesktopMacFrame: NSVisualEffectView class not found")
            return null
        }
        val selAlloc = o.sel_registerName("alloc")
        val selInit = o.sel_registerName("init")
        val alloc = send.invokePointer(arrayOf(effectClass, selAlloc)) ?: return null
        val view = send.invokePointer(arrayOf(alloc, selInit)) ?: return null

        val selSetTranslates = o.sel_registerName("setTranslatesAutoresizingMaskIntoConstraints:")
        send.invoke(arrayOf(view, selSetTranslates, false))

        // NSVisualEffectBlendingModeBehindWindow = 0
        val selSetBlending = o.sel_registerName("setBlendingMode:")
        send.invoke(arrayOf(view, selSetBlending, 0L))

        // NSVisualEffectStateActive = 1
        val selSetState = o.sel_registerName("setState:")
        send.invoke(arrayOf(view, selSetState, 1L))

        // Mask corners to match Compose's 10.dp rounded corners
        val selSetWantsLayer = o.sel_registerName("setWantsLayer:")
        send.invoke(arrayOf(view, selSetWantsLayer, true))
        val selLayer = o.sel_registerName("layer")
        val layer = send.invokePointer(arrayOf(view, selLayer))
        if (layer != null && layer != Pointer.NULL) {
            val selSetCornerRadius = o.sel_registerName("setCornerRadius:")
            val selSetMasksToBounds = o.sel_registerName("setMasksToBounds:")
            val radius = if (DesktopWindowMode.maximized.value || DesktopWindowMode.fullScreen.value) {
                0.0
            } else {
                10.0
            }
            send.invoke(arrayOf(layer, selSetCornerRadius, radius))
            send.invoke(arrayOf(layer, selSetMasksToBounds, true))
        }

        // Always a sibling behind AWT's contentView so the effect view cannot steal mouse events.
        val selAddSubview = o.sel_registerName("addSubview:positioned:relativeTo:")
        val parent = if (superview != contentView) superview else run {
            // contentView has no useful superview — still place below it if possible, else at back.
            superview
        }
        if (parent != contentView) {
            send.invoke(arrayOf(parent, selAddSubview, view, -1L, contentView))
        } else {
            send.invoke(arrayOf(contentView, selAddSubview, view, -1L, null))
            // Subview of the AWT root can sit above Skia; force it to the back of the stack.
            val selSendBack = o.sel_registerName("sendSubviewToBack:")
            runCatching { send.invoke(arrayOf(contentView, selSendBack, view)) }
        }

        pinConstraints(view, contentView)

        effectViewPtr = view
        applyDarkAppearance(nsWindow, view)
        DesktopTrackLog.log("DesktopMacFrame: NSVisualEffectView installed behind contentView")
        return view
    }

    /**
     * AppKit on recent macOS invokes `windowWillReturnFieldEditor:toObject:` on AWTWindow while
     * entering a full-screen Space. OpenJDK's AWTWindow never implemented that optional delegate
     * method, so the unrecognized selector aborts the JVM. Patch it once to return nil.
     */
    private fun ensureAwtWindowFieldEditorStub() {
        if (!fieldEditorStubInstalled.compareAndSet(false, true)) return
        val o = objc ?: return
        val awtWindowClass = o.objc_getClass("AWTWindow") ?: run {
            fieldEditorStubInstalled.set(false)
            return
        }
        val sel = o.sel_registerName("windowWillReturnFieldEditor:toObject:")
        val added = o.class_addMethod(awtWindowClass, sel, fieldEditorStubRef, "@@:@@")
        DesktopTrackLog.log(
            if (added) "DesktopMacFrame: installed AWTWindow field-editor stub"
            else "DesktopMacFrame: AWTWindow already has windowWillReturnFieldEditor:toObject:",
        )
    }

    private fun enableEawtFullScreen(window: Window) {
        runCatching {
            (window as? RootPaneContainer)?.rootPane?.putClientProperty("apple.awt.fullscreenable", true)
            val util = Class.forName("com.apple.eawt.FullScreenUtilities")
            util.getMethod("setWindowCanFullScreen", Window::class.java, java.lang.Boolean.TYPE)
                .invoke(null, window, true)
            if (!eawtListenerRegistered) {
                val listenerClass = Class.forName("com.apple.eawt.FullScreenListener")
                val listener = Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                ) { _, method, _ ->
                    when (method.name) {
                        "windowEnteredFullScreen" -> {
                            onNativeFullscreenChanged(entered = true)
                            DesktopTrackLog.log("DesktopMacFrame: eawt entered full-screen")
                        }
                        "windowExitedFullScreen" -> {
                            onNativeFullscreenChanged(entered = false)
                            DesktopTrackLog.log("DesktopMacFrame: eawt left full-screen")
                        }
                    }
                    null
                }
                eawtListenerRef = listener
                util.getMethod("addFullScreenListenerTo", Window::class.java, listenerClass)
                    .invoke(null, window, listener)
                eawtListenerRegistered = true
            }
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: eawt fullscreen setup failed: ${it.message}")
        }
    }

    private fun requestToggleFullScreen(window: Window): Boolean {
        val eawtOk = runCatching {
            val appClass = Class.forName("com.apple.eawt.Application")
            val app = appClass.getMethod("getApplication").invoke(null)
            appClass.getMethod("requestToggleFullScreen", Window::class.java).invoke(app, window)
            true
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: eawt requestToggleFullScreen unavailable: ${it.message}")
        }.getOrDefault(false)
        if (eawtOk) return true
        // Same path CPlatformWindow uses: -[NSWindow toggleFullScreen:]. Safe once the
        // windowWillReturnFieldEditor:toObject: stub is installed on AWTWindow.
        return dispatchToggleFullScreenToMainThread()
    }

    private fun dispatchToggleFullScreenToMainThread(): Boolean {
        if (windowPtr == null) return false
        if (isMainThread()) {
            return runCatching {
                toggleFullScreenOnMainThread()
                true
            }.getOrDefault(false)
        }
        val tu = threadUtilities ?: return runCatching {
            toggleFullScreenOnMainThread()
            true
        }.getOrDefault(false)
        val helper = helperInstance ?: return false
        val selPerform = selPerformOnMain ?: return false
        val selAction = selToggleFullScreenAction ?: return false
        val send = msgSend ?: return false
        return runCatching {
            send.invoke(arrayOf(tu, selPerform, selAction, helper, helper, false))
            true
        }.getOrDefault(false)
    }

    private fun toggleFullScreenOnMainThread() {
        val o = objc ?: return
        val send = msgSend ?: return
        val nsWindow = windowPtr ?: return
        enableFullScreenPrimary(nsWindow)
        val sel = o.sel_registerName("toggleFullScreen:")
        send.invoke(arrayOf(nsWindow, sel, null as Pointer?))
        DesktopTrackLog.log("DesktopMacFrame: invoked NSWindow toggleFullScreen:")
    }

    private fun enableFullScreenPrimary(nsWindow: Pointer) {
        val o = objc ?: return
        val send = msgSend ?: return
        runCatching {
            val selCollectionBehavior = o.sel_registerName("collectionBehavior")
            val current = (send.invoke(Long::class.java, arrayOf(nsWindow, selCollectionBehavior)) as? Number)
                ?.toLong() ?: 0L
            val selSetCollectionBehavior = o.sel_registerName("setCollectionBehavior:")
            send.invoke(
                arrayOf(
                    nsWindow,
                    selSetCollectionBehavior,
                    current or NS_WINDOW_COLLECTION_BEHAVIOR_FULL_SCREEN_PRIMARY,
                ),
            )
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: failed to enable full-screen primary: ${it.message}")
        }
    }

    private fun registerFullscreenObservers(nsWindow: Pointer) {
        if (fullscreenObserversRegistered) return
        val o = objc ?: return
        val send = msgSend ?: return
        val helper = helperInstance ?: return
        runCatching {
            val centerClass = o.objc_getClass("NSNotificationCenter") ?: return
            val selDefaultCenter = o.sel_registerName("defaultCenter")
            val center = send.invokePointer(arrayOf(centerClass, selDefaultCenter)) ?: return
            val nsStringClass = o.objc_getClass("NSString") ?: return
            val selUtf8 = o.sel_registerName("stringWithUTF8String:")
            val enterName = send.invokePointer(
                arrayOf(nsStringClass, selUtf8, "NSWindowDidEnterFullScreenNotification"),
            ) ?: return
            val exitName = send.invokePointer(
                arrayOf(nsStringClass, selUtf8, "NSWindowDidExitFullScreenNotification"),
            ) ?: return
            val selAdd = o.sel_registerName("addObserver:selector:name:object:")
            val selEnter = o.sel_registerName("didEnterFullScreen:")
            val selExit = o.sel_registerName("didExitFullScreen:")
            send.invoke(arrayOf(center, selAdd, helper, selEnter, enterName, nsWindow))
            send.invoke(arrayOf(center, selAdd, helper, selExit, exitName, nsWindow))
            fullscreenObserversRegistered = true
            DesktopTrackLog.log("DesktopMacFrame: full-screen observers registered")
        }.onFailure {
            DesktopTrackLog.log("DesktopMacFrame: failed to register full-screen observers: ${it.message}")
        }
    }

    private fun applyNativeBackdropOnMainThread(nativeKind: Int) {
        val o = objc ?: return
        val send = msgSend ?: return
        val nsWindow = windowPtr ?: return
        ensureResizableStyleMaskOnMainThread(nsWindow)
        enableFullScreenPrimary(nsWindow)
        registerFullscreenObservers(nsWindow)

        val effectView = ensureWindowAndEffectView(o, send, nsWindow) ?: return

        val selSetHidden = o.sel_registerName("setHidden:")
        val selSetMaterial = o.sel_registerName("setMaterial:")

        if (nativeKind == 0) { // OFF
            send.invoke(arrayOf(effectView, selSetHidden, true))
            DesktopTrackLog.log("DesktopMacFrame: backdrop set to OFF (hidden=true)")
        } else {
            // macOS Materials:
            // MICA (2) -> NSVisualEffectMaterialSidebar (7)
            // ACRYLIC (3) -> NSVisualEffectMaterialHUDWindow (13)
            val material = if (nativeKind == 2) 7L else 13L
            send.invoke(arrayOf(effectView, selSetMaterial, material))
            send.invoke(arrayOf(effectView, selSetHidden, false))
            DesktopTrackLog.log("DesktopMacFrame: backdrop applied material $material (nativeKind $nativeKind)")
        }
    }

    private fun pinConstraints(view: Pointer, target: Pointer) {
        val o = objc ?: return
        val send = msgSend ?: return

        val selLeading = o.sel_registerName("leadingAnchor")
        val selTrailing = o.sel_registerName("trailingAnchor")
        val selTop = o.sel_registerName("topAnchor")
        val selBottom = o.sel_registerName("bottomAnchor")
        val selConstraintEqualTo = o.sel_registerName("constraintEqualToAnchor:")
        val selSetActive = o.sel_registerName("setActive:")

        listOf(selLeading, selTrailing, selTop, selBottom).forEach { anchorSel ->
            val vAnchor = send.invokePointer(arrayOf(view, anchorSel)) ?: return@forEach
            val tAnchor = send.invokePointer(arrayOf(target, anchorSel)) ?: return@forEach
            val constraint = send.invokePointer(arrayOf(vAnchor, selConstraintEqualTo, tAnchor)) ?: return@forEach
            send.invoke(arrayOf(constraint, selSetActive, true))
        }
    }
}
