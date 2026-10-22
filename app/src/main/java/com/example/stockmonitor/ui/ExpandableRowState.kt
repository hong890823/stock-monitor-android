package com.example.stockmonitor.ui

open class ExpandableRowState {
    private val expandedKeys = mutableSetOf<String>()

    @Synchronized
    fun isExpanded(key: String): Boolean = key in expandedKeys

    @Synchronized
    fun toggle(key: String): Boolean {
        val expanded = if (expandedKeys.remove(key)) false else {
            expandedKeys.add(key)
            true
        }
        return expanded
    }

    fun allExpanded(keys: Iterable<String>): Boolean {
        val keyList = keys.toList()
        return keyList.isNotEmpty() && keyList.all(::isExpanded)
    }

    @Synchronized
    fun toggleAll(keys: Iterable<String>): Boolean {
        val keyList = keys.toList()
        if (keyList.isEmpty()) return false
        val expanded = !allExpanded(keyList)
        keyList.forEach { key ->
            if (expanded) expandedKeys.add(key) else expandedKeys.remove(key)
        }
        return expanded
    }
}

object QuoteRowExpansionState : ExpandableRowState()
