package com.aubynsamuel.clipsync.bluetooth

enum class SharingResult {
    SUCCESS,
    SENDING_ERROR,
    PERMISSION_NOT_GRANTED,
    NO_SELECTED_DEVICES,
    FILE_TOO_LARGE,

    /**
     * The user stopped this transfer from the history screen. Deliberately distinct from
     * [SENDING_ERROR]: a cancelled send must not be retried by the clipboard watcher and must
     * not surface as a failure toast.
     */
    CANCELLED
}