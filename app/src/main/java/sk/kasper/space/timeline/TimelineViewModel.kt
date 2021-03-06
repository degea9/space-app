package sk.kasper.space.timeline

import androidx.databinding.Bindable
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.threeten.bp.LocalDateTime
import org.threeten.bp.LocalTime
import sk.kasper.domain.model.ErrorResponse
import sk.kasper.domain.model.FilterSpec
import sk.kasper.domain.model.Launch
import sk.kasper.domain.model.SuccessResponse
import sk.kasper.domain.usecase.timeline.GetTimelineItems
import sk.kasper.domain.usecase.timeline.RefreshTimelineItems
import sk.kasper.space.BR
import sk.kasper.space.settings.SettingsManager
import sk.kasper.space.timeline.filter.TimelineFilterSpecModel
import sk.kasper.space.utils.ObservableViewModel
import sk.kasper.space.utils.SingleLiveEvent
import timber.log.Timber

open class TimelineViewModel @AssistedInject constructor(
        private val getTimelineItems: GetTimelineItems,
        private val refreshTimelineItems: RefreshTimelineItems,
        private val settingsManager: SettingsManager,
        @Assisted private val timelineFilterSpecModel: TimelineFilterSpecModel) : ObservableViewModel(), LaunchListItemViewModel.OnListInteractionListener {

    val timelineItems: MutableLiveData<List<TimelineListItem>> = MutableLiveData()
    val progressVisible: MutableLiveData<Boolean> = MutableLiveData()

    val connectionErrorEvent: SingleLiveEvent<Any> = SingleLiveEvent()
    val showLaunchDetailEvent: SingleLiveEvent<Long> = SingleLiveEvent()
    val showFilterEvent: SingleLiveEvent<Any> = SingleLiveEvent()

    @get:Bindable
    var showNoMatchingLaunches: Boolean = false

    @get:Bindable
    var showRetryToLoadLaunches: Boolean = false

    init {
        viewModelScope.launch {
            timelineFilterSpecModel.flow.collect {
                runLongOp {
                    loadTimeline(it)
                }
            }
        }
    }

    private suspend fun loadTimeline(filterSpec: FilterSpec) {
        Timber.d("LoadingResponse")
        val timelineItemsRaw = getTimelineItems.getTimelineItems(filterSpec)
        val listItems = mapToTimeListItem(timelineItemsRaw)
        Timber.d("SuccessResponse size: ${listItems.size}")
        timelineItems.value = listItems
        showNoMatchingLaunches = listItems.isEmpty() && filterSpec.filterNotEmpty()
        showRetryToLoadLaunches = listItems.isEmpty() && !filterSpec.filterNotEmpty()
        notifyPropertyChanged(BR.showNoMatchingLaunches)
        notifyPropertyChanged(BR.showRetryToLoadLaunches)
    }

    private fun mapToTimeListItem(list: List<Launch>): List<TimelineListItem> {
        val items: MutableList<TimelineListItem> = mutableListOf()
        val currentDateTime = getCurrentDateTime()
        val todayStartDateTime = LocalDateTime.of(currentDateTime.toLocalDate(), LocalTime.MIDNIGHT) // start of this day
        val todayEndDateTime = todayStartDateTime.plusDays(1) // end of this day
        val tomorrowEndDateTime = todayEndDateTime.plusDays(1) // end of next day
        val weekLaterDateTime = todayEndDateTime.plusDays(6)
        val showUnconfirmedLaunches = settingsManager.showUnconfirmedLaunches

        list
            .filter { showUnconfirmedLaunches || it.accurateDate }
            .filter { it.launchDateTime.isAfter(todayStartDateTime) }
            .map { LaunchListItem.fromLaunch(it) }
            .groupBy {
                when {
                    !it.accurateDate -> LabelListItem.Month(it.launchDateTime.monthValue)
                    it.launchDateTime.isBefore(todayEndDateTime) -> LabelListItem.Today
                    it.launchDateTime.isBefore(tomorrowEndDateTime) -> LabelListItem.Tomorrow
                    it.launchDateTime.isBefore(weekLaterDateTime) -> LabelListItem.ThisWeek
                    else -> LabelListItem.Month(it.launchDateTime.monthValue)
                }
            }
            .toSortedMap(labelListItemsComparator())
            .forEach {
                items.add(it.key)
                items.addAll(it.value.sortedWith(launchListItemsComparator()))
            }

        return items
    }

    private fun labelListItemsComparator() = Comparator { o1: LabelListItem, o2: LabelListItem ->
        o1.compareTo(o2)
    }

    // accurate items go first
    private fun launchListItemsComparator(): java.util.Comparator<LaunchListItem> {
        return Comparator { o1: LaunchListItem, o2: LaunchListItem ->
            if (o1.accurateDate xor o2.accurateDate) { // accuracy values are different
                if (o1.accurateDate) -1 else 1
            } else {
                o1.launchDateTime.compareTo(o2.launchDateTime)
            }
        }
    }

    fun onFilterBarClick() {
        showFilterEvent.call()
    }

    fun onRefresh() {
        viewModelScope.launch {
            runLongOp {
                when (refreshTimelineItems.refresh()) {
                    is SuccessResponse -> loadTimeline(timelineFilterSpecModel.value)
                    is ErrorResponse -> connectionErrorEvent.call()
                }
            }
        }
    }

    private suspend fun runLongOp(block: suspend () -> Unit) {
        progressVisible.value = true
        block()
        progressVisible.value = false
    }

    override fun onItemClick(item: LaunchListItem) {
        showLaunchDetailEvent.value = item.id
    }

    open fun getCurrentDateTime(): LocalDateTime = LocalDateTime.now()

    @AssistedInject.Factory
    interface Factory {
        fun create(timelineFilterSpecModel: TimelineFilterSpecModel): TimelineViewModel
    }

}
