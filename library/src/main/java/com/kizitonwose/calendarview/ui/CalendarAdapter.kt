package com.kizitonwose.calendarview.ui

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import com.kizitonwose.calendarview.CalendarView
import com.kizitonwose.calendarview.model.*
import com.kizitonwose.calendarview.utils.NO_INDEX
import com.kizitonwose.calendarview.utils.inflate
import com.kizitonwose.calendarview.utils.orZero
import org.threeten.bp.DayOfWeek
import org.threeten.bp.LocalDate
import org.threeten.bp.YearMonth
import kotlin.math.sign

internal typealias LP = ViewGroup.LayoutParams

data class ViewConfig(
    @LayoutRes val dayViewRes: Int,
    @LayoutRes val monthHeaderRes: Int,
    @LayoutRes val monthFooterRes: Int,
    val monthViewClass: String?
)

class CalendarAdapter(
    internal var viewConfig: ViewConfig,
    internal var monthConfig: MonthConfig,
    private val calView: CalendarView,
    private val startMonth: YearMonth,
    private val endMonth: YearMonth,
    private val firstDayOfWeek: DayOfWeek
) : RecyclerView.Adapter<MonthViewHolder>() {

    private var months = CalendarMonthGenerator.generate(startMonth, endMonth, firstDayOfWeek, monthConfig)

    val bodyViewId = View.generateViewId()
    val rootViewId = View.generateViewId()

    // Values of headerViewId & footerViewId will be
    // replaced with IDs set in the XML if present.
    var headerViewId = View.generateViewId()
    var footerViewId = View.generateViewId()

    init {
        setHasStableIds(true)
    }

    fun generateMonths() {
        months = CalendarMonthGenerator.generate(startMonth, endMonth, firstDayOfWeek, monthConfig)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        calView.post { notifyMonthScrollListenerIfNeeded() }
    }

    private fun getItem(position: Int): CalendarMonth = months[position]

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    override fun getItemCount(): Int = months.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonthViewHolder {
        val context = parent.context
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            id = rootViewId
            clipChildren = false //#ClipChildrenFix
        }

        if (viewConfig.monthHeaderRes != 0) {
            val monthHeaderView = rootLayout.inflate(viewConfig.monthHeaderRes)
            // Don't overwrite ID set by the user.
            if (monthHeaderView.id == View.NO_ID) {
                monthHeaderView.id = headerViewId
            } else {
                headerViewId = monthHeaderView.id
            }
            rootLayout.addView(monthHeaderView)
        }

        val monthBodyLayout = LinearLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LP.WRAP_CONTENT, LP.WRAP_CONTENT)
            orientation = LinearLayout.VERTICAL
            id = bodyViewId
            clipChildren = false //#ClipChildrenFix
        }
        rootLayout.addView(monthBodyLayout)

        if (viewConfig.monthFooterRes != 0) {
            val monthFooterView = rootLayout.inflate(viewConfig.monthFooterRes)
            // Don't overwrite ID set by the user.
            if (monthFooterView.id == View.NO_ID) {
                monthFooterView.id = footerViewId
            } else {
                footerViewId = monthFooterView.id
            }
            rootLayout.addView(monthFooterView)
        }

        fun setupRoot(root: ViewGroup) {
            root.setPaddingRelative(
                calView.monthPaddingStart, calView.monthPaddingTop,
                calView.monthPaddingEnd, calView.monthPaddingBottom
            )
            root.layoutParams = ViewGroup.MarginLayoutParams(LP.WRAP_CONTENT, LP.WRAP_CONTENT).apply {
                bottomMargin = calView.monthMarginBottom
                topMargin = calView.monthMarginTop
                marginStart = calView.monthMarginStart
                marginEnd = calView.monthMarginEnd
            }
        }

        val userRoot = if (viewConfig.monthViewClass != null) {
            (Class.forName(viewConfig.monthViewClass)
                .getDeclaredConstructor(Context::class.java)
                .newInstance(context) as ViewGroup).apply {
                setupRoot(this)
                addView(rootLayout)
            }
        } else rootLayout.apply { setupRoot(this) }

        @Suppress("UNCHECKED_CAST")
        return MonthViewHolder(
            this, userRoot,
            DayConfig(
                calView.dayWidth, calView.dayHeight, viewConfig.dayViewRes,
                calView.dayBinder as DayBinder<ViewContainer>
            ),
            calView.monthHeaderBinder as MonthHeaderFooterBinder<ViewContainer>?,
            calView.monthFooterBinder as MonthHeaderFooterBinder<ViewContainer>?
        )
    }

    override fun onBindViewHolder(holder: MonthViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            payloads.forEach {
                holder.reloadDay(it as CalendarDay)
            }
        }
    }

    override fun onBindViewHolder(holder: MonthViewHolder, position: Int) {
        holder.bindMonth(getItem(position))
    }

    fun reloadDay(day: CalendarDay) {
        val position = getAdapterPosition(day)
        if (position != NO_INDEX) {
            notifyItemChanged(position, day)
        }
    }

    fun reloadMonth(month: YearMonth) {
        notifyItemChanged(getAdapterPosition(month))
    }

    private var visibleMonth: CalendarMonth? = null
    private var calWrapsHeight: Boolean? = null
    fun notifyMonthScrollListenerIfNeeded() {
        val visibleItemPos = findFirstVisibleMonthPosition()
        if (visibleItemPos != RecyclerView.NO_POSITION) {
            val visibleMonth = months[visibleItemPos]

            if (visibleMonth != this.visibleMonth) {
                this.visibleMonth = visibleMonth
                calView.monthScrollListener?.invoke(visibleMonth)

                // Fixes issue where the calendar does not resize its height when in horizontal, paged mode and
                // the `outDateStyle` is not `endOfGrid` hence the last row of a 5-row visible month is empty.
                // We set such week row's container visibility to GONE in the WeekHolder but it seems the
                // RecyclerView accounts for the items in the immediate previous and next indices when
                // calculating height and uses the tallest one of the three meaning that the current index's
                // view will end up having a blank space at the bottom unless the immediate previous and next
                // indices are also missing the last row. I think there should be a better way to fix this.
                if (calView.isHorizontal && calView.scrollMode == ScrollMode.PAGED) {
                    val calWrapsHeight = calWrapsHeight ?: (calView.layoutParams.height == LP.WRAP_CONTENT).also {
                        // We modify the layoutParams so we save the initial value set by the user.
                        calWrapsHeight = it
                    }
                    if (calWrapsHeight.not()) return // Bug only happens when the CalenderView wraps its height.
                    val visibleVH = calView.findViewHolderForAdapterPosition(visibleItemPos) as MonthViewHolder
                    val newHeight = visibleVH.headerView?.height.orZero() +
                            // For some reason `visibleVH.bodyLayout.height` does not give us the updated height.
                            // So we calculate it again by checking the number of visible(non-empty) rows.
                            visibleMonth.weekDays.takeWhile { it.isNotEmpty() }.size * calView.dayHeight +
                            visibleVH.footerView?.height.orZero()
                    if (calView.layoutParams.height != newHeight)
                        calView.layoutParams = calView.layoutParams.apply {
                            this.height = newHeight
                            // If we reset the calendar's height from a short item view's height(month with 5 rows)
                            // to a longer one(month with 6 rows), the row outside the old height is not drawn.
                            // This is fixed by setting `clipChildren = false` on all parents. #ClipChildrenFix
                        }
                }
            }
        }
    }

    internal fun getAdapterPosition(month: YearMonth): Int {
        return months.indexOfFirst { it.yearMonth == month }
    }

    internal fun getAdapterPosition(date: LocalDate): Int {
        return getAdapterPosition(CalendarDay(date, DayOwner.THIS_MONTH))
    }

    internal fun getAdapterPosition(day: CalendarDay): Int {
        val firstMonthIndex = getAdapterPosition(day.positionYearMonth)
        if (firstMonthIndex == NO_INDEX) return NO_INDEX

        val firstCalMonth = months[firstMonthIndex]
        val sameMonths = months.slice(firstMonthIndex until firstMonthIndex + firstCalMonth.numberOfSameMonth)
        val indexWithDateInSameMonth = sameMonths.indexOfFirst { month ->
            month.weekDays.flatten().any { it.date == day.date }
        }

        return if (indexWithDateInSameMonth == NO_INDEX) NO_INDEX else firstMonthIndex + indexWithDateInSameMonth
    }

    private val layoutManager: CalendarLayoutManager
        get() = calView.layoutManager as CalendarLayoutManager

    private fun findFirstVisibleMonthPosition(): Int {
        val visibleItemPos = layoutManager.findFirstVisibleItemPosition()
        if (visibleItemPos != RecyclerView.NO_POSITION) {
            // We make sure that the view for the returned position has at least one fully visible date cell.
            val visibleItemPx = Rect().let { rect ->
                val visibleItemView = layoutManager.findViewByPosition(visibleItemPos) ?: return NO_INDEX
                visibleItemView.getGlobalVisibleRect(rect)
                return@let if (calView.isVertical) {
                    rect.bottom - rect.top
                } else {
                    rect.right - rect.left
                }
            }

            // Fixes an issue where using DAY_SIZE_SQUARE with a paged calendar causes
            // some dates to stretch slightly outside the intended bounds due to pixel
            // rounding. Hence finding the first visible index will return the view
            // with the px outside bounds. 7 is the number of cells in a week.
            if (visibleItemPx <= 7) {
                val nextItemPosition = visibleItemPos + 1
                return if (months.indices.contains(nextItemPosition)) {
                    nextItemPosition
                } else {
                    visibleItemPos
                }
            }
        }

        return visibleItemPos
    }

    fun findFirstVisibleMonth(): CalendarMonth? =
        months.getOrNull(findFirstVisibleMonthPosition())

    fun findFirstVisibleDay(): CalendarDay? {
        val visibleMonthIndex = findFirstVisibleMonthPosition()
        if (visibleMonthIndex == NO_INDEX) return null

        val visibleItemView = layoutManager.findViewByPosition(visibleMonthIndex) ?: return null

        val isZeroOrPositive = { value: Int -> value.sign == 0 || value.sign == 1 }
        val dayRect = Rect()
        return months[visibleMonthIndex].weekDays.flatten().firstOrNull {
            val dayView = visibleItemView.findViewById<View?>(it.date.hashCode()) ?: return@firstOrNull false
            dayView.getGlobalVisibleRect(dayRect)
            return@firstOrNull isZeroOrPositive(if (calView.isVertical) dayRect.top else dayRect.left)
        }
    }

    fun findFirstCompletelyVisibleMonth(): CalendarMonth? =
        months.getOrNull(layoutManager.findFirstCompletelyVisibleItemPosition())


}