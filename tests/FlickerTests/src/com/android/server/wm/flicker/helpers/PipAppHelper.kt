/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.Assert

class PipAppHelper(instr: Instrumentation) : FlickerAppHelper(instr, "PipApp") {
    fun clickEnterPipButton(device: UiDevice) {
        val enterPipButton = device.findObject(By.res(getPackage(), "enter_pip"))
        Assert.assertNotNull("Pip button not found, this usually happens when the device " +
                "was left in an unknown state (e.g. in split screen)", enterPipButton)
        enterPipButton.click()
        AutomationUtils.hasPipWindow(device)
    }

    fun closePipWindow(device: UiDevice) {
        AutomationUtils.closePipWindow(device)
    }
}
