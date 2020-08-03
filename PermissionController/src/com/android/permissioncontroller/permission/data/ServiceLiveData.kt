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

package com.android.permissioncontroller.permission.data

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserHandle
import android.view.inputmethod.InputMethod
import com.android.permissioncontroller.DumpableLog
import com.android.permissioncontroller.PermissionControllerApplication
import com.android.permissioncontroller.permission.service.DEBUG_AUTO_REVOKE
import com.android.permissioncontroller.permission.utils.Utils.getUserContext
import kotlinx.coroutines.Job

/**
 * A LiveData which tracks services for a certain type
 *
 * @param app The current application
 * @param intentAction The name of interface the service implements
 * @param permission The permission required for the service
 * @param user The user the services should be determined for
 */
class ServiceLiveData(
    private val app: Application,
    override val intentAction: String,
    private val permission: String,
    private val user: UserHandle
) : SmartAsyncMediatorLiveData<Set<String>>(),
        PackageBroadcastReceiver.PackageBroadcastListener,
        HasIntentAction {

    private val name = intentAction.substringAfterLast(".")

    private val enabledAccessibilityServicesLiveData = EnabledAccessibilityServicesLiveData[user]
    private val enabledInputMethodsLiveData = EnabledInputMethodsLiveData[user]

    init {
        if (intentAction == AccessibilityService.SERVICE_INTERFACE) {
            addSource(enabledAccessibilityServicesLiveData) {
                updateAsync()
            }
        }
        if (intentAction == InputMethod.SERVICE_INTERFACE) {
            addSource(enabledInputMethodsLiveData) {
                updateAsync()
            }
        }
    }

    override fun onPackageUpdate(packageName: String) {
        updateAsync()
    }

    override suspend fun loadDataAndPostValue(job: Job) {
        if (job.isCancelled) {
            return
        }
        if (!enabledAccessibilityServicesLiveData.isInitialized) {
            return
        }
        if (!enabledInputMethodsLiveData.isInitialized) {
            return
        }

        val packageNames = getUserContext(app, user).packageManager
                .queryIntentServices(
                        Intent(intentAction),
                        PackageManager.GET_SERVICES or PackageManager.GET_META_DATA)
                .mapNotNull { resolveInfo ->
                    if (resolveInfo?.serviceInfo?.permission != permission) {
                        return@mapNotNull null
                    }
                    val packageName = resolveInfo?.serviceInfo?.packageName
                    if (!isServiceEnabled(packageName)) {
                        if (DEBUG_AUTO_REVOKE) {
                            DumpableLog.i(LOG_TAG,
                                    "Not exempting $packageName - not an active $name " +
                                            "for u${user.identifier}")
                        }
                        return@mapNotNull null
                    }
                    packageName
                }.toSet()
        if (DEBUG_AUTO_REVOKE) {
            DumpableLog.i(LOG_TAG,
                    "Detected ${name}s: $packageNames")
        }

        postValue(packageNames)
    }

    suspend fun isServiceEnabled(pkg: String?): Boolean {
        if (pkg == null) {
            return false
        }
        return when (intentAction) {
            AccessibilityService.SERVICE_INTERFACE -> {
                pkg in enabledAccessibilityServicesLiveData.value!!
            }
            InputMethod.SERVICE_INTERFACE -> {
                pkg in enabledInputMethodsLiveData.value!!
            }
            // TODO(eugenesusla): fill in check implementations for most service types
            else -> true
        }
    }

    override fun onActive() {
        super.onActive()

        PackageBroadcastReceiver.addAllCallback(this)

        updateAsync()
    }

    override fun onInactive() {
        super.onInactive()

        PackageBroadcastReceiver.removeAllCallback(this)
    }

    /**
     * Repository for [ServiceLiveData]
     *
     * <p> Key value is a (string service name, required permission, user) triple, value is its
     * corresponding LiveData.
     */
    companion object : DataRepositoryForPackage<Triple<String, String, UserHandle>,
            ServiceLiveData>() {
        private const val LOG_TAG = "ServiceLiveData"

        override fun newValue(key: Triple<String, String, UserHandle>): ServiceLiveData {
            return ServiceLiveData(PermissionControllerApplication.get(),
                    key.first, key.second, key.third)
        }
    }
}
