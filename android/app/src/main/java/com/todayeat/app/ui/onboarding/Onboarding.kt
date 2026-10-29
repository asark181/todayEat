package com.todayeat.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.todayeat.app.ui.AppViewModel
import com.todayeat.app.ui.common.LoginSheet
import com.todayeat.app.ui.common.PlanFields
import com.todayeat.app.ui.components.Card
import com.todayeat.app.ui.components.LocalSheets
import com.todayeat.app.ui.components.PrimaryButton
import com.todayeat.app.ui.components.TextLink
import com.todayeat.app.ui.home.StateMark
import com.todayeat.app.domain.DayState
import com.todayeat.app.ui.theme.T

/** 第一次打开：说清楚它做什么，然后定好第一个提醒 */
@Composable
fun Onboarding(vm: AppViewModel, onDone: () -> Unit) {
    val c = T.c
    val sheets = LocalSheets.current
    var plan by remember { mutableStateOf(vm.newPlan()) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(top = 48.dp, bottom = 24.dp)
    ) {
        StateMark(DayState.ALL, 52.dp)
        Spacer(Modifier.height(24.dp))
        Text("吃完药又睡着了，\n醒来记不清吃没吃？", style = T.display, color = c.ink)
        Spacer(Modifier.height(12.dp))
        Text(
            "到点它会提醒你。吃完在通知上点一下「已吃」就记上了，还可以拍张照留底。醒来打开，或者看一眼桌面小组件，就知道今天吃过没有。",
            style = T.body, color = c.ink2
        )
        Spacer(Modifier.height(4.dp))
        TextLink("以前用过？登录把记录找回来", modifier = Modifier.offset(x = (-6).dp)) {
            sheets.show { close ->
                LoginSheet(vm.app.sync, false, close) {
                    vm.markOnboarded()
                    onDone()
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("先定第一个提醒", style = T.tiny, color = c.ink3, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Card {
            PlanFields(plan, onChange = { plan = it })
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("好，就这样", Modifier.fillMaxWidth(), enabled = plan.name.isNotBlank()) {
            vm.finishOnboarding(plan.copy(name = plan.name.trim(), dose = plan.dose.trim()))
            onDone()
        }
    }
}
