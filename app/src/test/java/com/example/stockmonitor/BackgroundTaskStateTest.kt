package com.example.stockmonitor

import com.example.stockmonitor.monitor.BackgroundTaskState
import com.example.stockmonitor.monitor.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTaskStateTest {

    @Test
    fun runningTaskShowsProgressAndIsActive() {
        val task = BackgroundTaskState.running("持仓分析", 2, 5)

        assertTrue(task.isActive)
        assertEquals(TaskPhase.RUNNING, task.phase)
        assertEquals("2/5", task.progressText)
    }

    @Test
    fun completedTaskKeepsCompletionTimeAndIsNotActive() {
        val task = BackgroundTaskState.success("盘面总结", "21:05")

        assertFalse(task.isActive)
        assertEquals(TaskPhase.SUCCESS, task.phase)
        assertEquals("已完成 · 21:05", task.displayText)
    }

    @Test
    fun failedTaskExposesReason() {
        val task = BackgroundTaskState.failure("持仓分析", "网络超时", "21:06")

        assertFalse(task.isActive)
        assertEquals(TaskPhase.FAILURE, task.phase)
        assertEquals("失败 · 网络超时 · 21:06", task.displayText)
    }
}
