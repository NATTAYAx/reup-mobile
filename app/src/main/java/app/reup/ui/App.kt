package app.reup.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.reup.R
import app.reup.core.paletteOf

/**
 * Everything the screens ask for that only an activity can do: open another
 * screen, open a page of the system's own settings.
 *
 * An interface rather than lambdas threaded through every call, so a screen
 * says what it wants - "open sync" - and does not need to know which class
 * answers it. Round three replaces some of these with screens of their own,
 * and nothing that calls them will notice.
 */
interface AppActions {
    fun addTask()
    fun editTask(uid: String)

    /** The sheet for sticking up a note, or for changing the one with [uid]. */
    fun addNote()
    fun editNote(uid: String)
    fun addMoney(incoming: Boolean)
    fun editMoney(uid: String)
    fun openSync()
    fun openSlip()
    fun openNotificationSettings()
    fun openBatterySettings()
    fun openHibernationSettings()
    fun openExactAlarmSettings()
    fun openAppSettings()
    fun fireTest()

    /** Ask for the gallery, for slips that arrive on their own. Answered through HomeModel.photoAccessAnswered. */
    fun requestPhotoAccess()

    /** Open a waiting slip in the money form, filled in. */
    fun editSlip(id: Long)

    /** Show a waiting slip's picture whole, with its save, edit and don't-save. */
    fun viewSlip(id: Long)

    /** Open money in, read off a bank's notification, in the money form, filled in. */
    fun editIncome(id: String)

    /** The phone's own page for letting this app read notifications. */
    fun openNotificationAccess()

    /** The photo picker, for the widget's picture: one picture or clip, and nothing else. Answered through WidgetModel.picked. */
    fun pickWidgetPicture()

    /** Ask the home screen to take a widget, or say how to add one where it cannot be asked. */
    fun placeWidget()

    /** The photo picker, for the wallpaper's clip: videos only. Answered through WallModel.picked. */
    fun pickWallpaperClip()

    /** The system's own page for setting this app's moving wallpaper. */
    fun setWallpaper()
}

/** The three tabs, in the bar's order. Public so the widget can ask for one. */
enum class HomeTab { TODAY, MONEY, SETTINGS }

/**
 * The app: three tabs and a bar, and one screen opened from a tab.
 *
 * WHY THREE TABS
 *
 * The old screen was one long column: the list, then the settings, then the
 * colours, then a debugging panel, with the money one button away. Everything
 * was reachable and nothing had a place. The three things this app is used for
 * - what to do today, what money went where, and how the phone is set up - get
 * one tab each, and the bar says which one is open.
 */
@Composable
fun ReupApp(model: HomeModel, actions: AppActions) {
    val colors = remember(model.themeId) { reupColors(paletteOf(model.themeId)) }
    ReupTheme(colors) {
        var tab by rememberSaveable { mutableIntStateOf(HomeTab.TODAY.ordinal) }
        var diagnostics by rememberSaveable { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }

        // Back leaves the inner screen first, then returns to Today, then
        // leaves the app - the order a person expects from where they are.
        BackHandler(enabled = diagnostics) { diagnostics = false }
        BackHandler(enabled = !diagnostics && tab != HomeTab.TODAY.ordinal) { tab = HomeTab.TODAY.ordinal }

        // A tab asked for from outside the screens: a tap on the widget.
        val ask = model.tabAsk
        LaunchedEffect(ask?.id) {
            if (ask == null) return@LaunchedEffect
            diagnostics = false
            tab = ask.tab.ordinal
        }

        val notice = model.notice
        LaunchedEffect(notice?.id) {
            if (notice == null) return@LaunchedEffect
            val result = snackbar.showSnackbar(
                message = notice.text,
                actionLabel = notice.action,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) notice.onAction?.invoke()
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(Reup.colors.bg),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                        ),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // The reading column stops widening at a tablet's width;
                    // past it the extra room is margin, not longer lines.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .widthIn(max = Size.reading),
                    ) {
                        if (diagnostics) {
                            DiagnosticsScreen(model, actions, onBack = { diagnostics = false })
                        } else {
                            val lift by animateDpAsState(
                                targetValue = if (snackbar.currentSnackbarData != null) 72.dp else 0.dp,
                                label = "fab",
                            )
                            when (tab) {
                                HomeTab.MONEY.ordinal -> MoneyScreen(model, actions, fabLift = lift)
                                HomeTab.SETTINGS.ordinal -> SettingsScreen(model, actions, openDiagnostics = { diagnostics = true })
                                else -> TodayScreen(model, actions, fabLift = lift)
                            }
                        }
                        SnackbarHost(
                            hostState = snackbar,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        ) { data ->
                            Snackbar(
                                snackbarData = data,
                                shape = RoundedCornerShape(16.dp),
                                containerColor = Reup.colors.raised,
                                contentColor = Reup.colors.text,
                                actionColor = Reup.colors.accent,
                            )
                        }
                    }
                }
                if (!diagnostics) {
                    BottomBar(
                        items = listOf(
                            BarItem("วันนี้", R.drawable.ic_ring),
                            // A dot while something is waiting for a yes or a
                            // no: money in has no notification of its own, the
                            // bank's already said it, so this is where it shows.
                            BarItem(
                                "เงิน",
                                R.drawable.ic_wallet,
                                badge = model.slips.isNotEmpty() || model.incomes.isNotEmpty(),
                                badgeColor = Reup.colors.accent,
                            ),
                            BarItem("ตั้งค่า", R.drawable.ic_settings, badge = model.warnings.isNotEmpty()),
                        ),
                        selected = tab,
                        onSelect = { picked ->
                            if (picked == HomeTab.MONEY.ordinal) model.loadMoney()
                            tab = picked
                        },
                    )
                }
            }
        }
    }
}
