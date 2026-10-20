package com.example.stockmonitor.ui

import android.os.Bundle
import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.LayoutRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.example.stockmonitor.R
import com.example.stockmonitor.monitor.MonitorService
import com.example.stockmonitor.monitor.Repo
import kotlinx.coroutines.CoroutineScope

/** 统一的下拉刷新 + 卡片容器结构，并监听 Repo 状态变化。 */
abstract class BaseFragment(@LayoutRes layout: Int) : Fragment(layout) {

    protected var swipe: SwipeRefreshLayout? = null
    protected var content: LinearLayout? = null
    private var imeVisible = false
    private var deferredRender = false
    private var imeLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private val listener: () -> Unit = {
        val textInputFocused = view?.findFocus() is EditText
        if (UiInteractionPolicy.shouldDeferRender(textInputFocused, imeVisible)) {
            deferredRender = true
        } else {
            render()
        }
    }
    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (deferredRender && !imeVisible && newFocus !is EditText) {
            deferredRender = false
            view?.post { if (isAdded && view != null) render() }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipe = view.findViewById(R.id.swipe)
        content = view.findViewById(R.id.content)
        swipe?.setOnRefreshListener { onRefresh() }
        view.viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            updateImeVisibility(view, insets.isVisible(WindowInsetsCompat.Type.ime()), insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }
        imeLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val frame = Rect()
            view.getWindowVisibleDisplayFrame(frame)
            updateImeVisibility(view, false, 0, frame.bottom)
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(imeLayoutListener)
        ViewCompat.requestApplyInsets(view)
        Repo.addListener(listener)
        render()
    }

    override fun onDestroyView() {
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(focusListener)
        imeLayoutListener?.let { view?.viewTreeObserver?.removeOnGlobalLayoutListener(it) }
        view?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) }
        deferredRender = false
        imeVisible = false
        Repo.removeListener(listener)
        swipe = null
        content = null
        super.onDestroyView()
    }

    private fun updateImeVisibility(
        root: View,
        insetsVisible: Boolean,
        imeBottom: Int,
        visibleFrameBottom: Int? = null,
    ) {
        val wasImeVisible = imeVisible
        val frame = visibleFrameBottom ?: run {
            val visibleFrame = Rect()
            root.getWindowVisibleDisplayFrame(visibleFrame)
            visibleFrame.bottom
        }
        imeVisible = UiInteractionPolicy.isImeVisible(
            insetsVisible = insetsVisible,
            imeBottom = imeBottom,
            rootHeight = root.rootView.height,
            visibleFrameBottom = frame,
            threshold = UiKit.dp(root.context, 160),
        )
        if (wasImeVisible && !imeVisible && deferredRender && root.findFocus() !is EditText) {
            deferredRender = false
            root.post { if (isAdded) render() }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** 下拉刷新：默认请求后台服务立即刷新一轮。 */
    protected open fun onRefresh() {
        MonitorService.send(requireContext(), MonitorService.ACTION_REFRESH)
    }

    protected fun rebuild(block: (LinearLayout) -> Unit) {
        val container = content ?: return
        swipe?.isRefreshing = Repo.refreshing
        container.removeAllViews()
        block(container)
    }

    /** 生命周期内的协程作用域，用于对话框触发的网络请求。 */
    protected fun requireScope(): CoroutineScope = viewLifecycleOwner.lifecycleScope

    abstract fun render()
}
