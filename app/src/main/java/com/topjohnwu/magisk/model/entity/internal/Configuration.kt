package com.topjohnwu.magisk.model.entity.internal

import android.net.Uri
import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

sealed class Configuration : Parcelable {

    sealed class Flash : Configuration() {

        @Parcelize
        object Primary : Flash()

        @Parcelize
        object Secondary : Flash()

    }

    @Parcelize
    object Download : Configuration()

    @Parcelize
    object Uninstall : Configuration()

    @Parcelize
    data class Patch(val fileUri: Uri) : Configuration()

}