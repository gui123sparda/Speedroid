package com.example.tunerstudioandroid

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.doOnLayout
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class MapViewport(context: Context) : LinearLayout(context) {
    data class Position(val x: Int, val y: Int)

    private var horizontal: HorizontalScrollView? = null
    private var vertical: ScrollView? = null

    init {
        orientation = VERTICAL
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun row() = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun touchRowHeight() = dp(ceil(48 * resources.configuration.fontScale.coerceAtLeast(1f)).toInt())

    fun position() = Position(horizontal?.scrollX ?: 0, vertical?.scrollY ?: 0)

    fun restore(position: Position) {
        val body = vertical ?: return
        val sheet = horizontal
        body.doOnLayout {
            sheet?.scrollTo(position.x, 0)
            body.scrollTo(0, position.y.coerceIn(0, body.getChildAt(0).height))
        }
    }

    fun table(corner: TextView, columns: List<TextView>, rows: List<TextView>, cells: List<List<TextView>>) {
        removeAllViews()
        val labelWidth = widthFor(listOf(corner) + rows)
        val cellWidth = widthFor(columns + cells.flatten())

        val footer = row()
        footer.addCell(corner, labelWidth)
        val columnLabels = row().apply { columns.forEach { addCell(it, cellWidth) } }
        val headerScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(columnLabels)
        }
        footer.addView(headerScroll, LayoutParams(0, -2, 1f))

        val labels = column().apply { rows.forEach { addCell(it, labelWidth) } }
        val matrix = column()
        cells.forEach { line ->
            matrix.addView(row().apply { line.forEach { addCell(it, cellWidth) } })
        }
        val sheet = HorizontalScrollView(context).apply {
            addView(matrix)
            isHorizontalScrollBarEnabled = true
        }
        horizontal = sheet
        // Keep the RPM headings aligned whether the user pans the values or the headings.
        sheet.setOnScrollChangeListener { _, x, _, _, _ -> headerScroll.scrollTo(x, 0) }
        headerScroll.setOnScrollChangeListener { _, x, _, _, _ -> sheet.scrollTo(x, 0) }

        val body = row().apply {
            gravity = Gravity.TOP
            addView(labels)
            addView(sheet, LayoutParams(0, -2, 1f))
        }
        scrollingBody(body)
        addView(footer, LayoutParams(-1, -2))
    }

    fun curve(headers: List<TextView>, points: List<List<TextView>>) {
        removeAllViews()
        horizontal = null
        val head = row()
        headers.forEach { head.addWeighted(it) }
        addView(head, LayoutParams(-1, -2))
        val body = column()
        points.forEach { line ->
            body.addView(row().apply { line.forEach { addWeighted(it) } })
        }
        scrollingBody(body)
    }

    private fun scrollingBody(body: View) {
        val scroll = ScrollView(context).apply { addView(body) }
        vertical = scroll
        addView(scroll, LayoutParams(-1, 0, 1f))
    }

    private fun widthFor(labels: List<TextView>): Int {
        val widest = labels.maxOfOrNull { ceil(it.paint.measureText(it.text.toString())).toInt() } ?: 0
        return maxOf(dp(58), widest + dp(12))
    }

    private fun LinearLayout.addCell(view: View, width: Int) {
        addView(view, LayoutParams(width, touchRowHeight()).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
    }

    private fun LinearLayout.addWeighted(view: View) {
        view.minimumHeight = touchRowHeight()
        addView(view, LayoutParams(0, -2, 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
    }
}
