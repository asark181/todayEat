package com.todayeat.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.todayeat.app.ui.theme.T

/** 第一次打开：加第一种药，或者登录找回 */
@Composable
fun Onboarding(vm: AppViewModel, onDone: () -> Unit) {
    val c = T.c
    val sheets = LocalSheets.current
    var plan by remember { mutableStateOf(vm.newPlan().copy(name = "早上的药")) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp)
            .padding(top = 32.dp, bottom = 24.dp)
    ) {
        Text("添加第一种药", style = T.display, color = c.ink)
        Text("到点提醒，打卡后可在日历里查看", style = T.body, color = c.ink2, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(24.dp))
        Card {
            PlanFields(plan, showActive = false, onChange = { plan = it })
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("完成", Modifier.fillMaxWidth(), enabled = plan.name.isNotBlank()) {
            vm.finishOnboarding(plan.copy(name = plan.name.trim(), dose = plan.dose.trim()))
            onDone()
        }
        Spacer(Modifier.height(8.dp))
        TextLink("已有账号，登录", style = T.body, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            sheets.show { close ->
                LoginSheet(vm.app.sync, false, close) {
                    vm.markOnboarded()
                    onDone()
                }
            }
        }
    }
}
