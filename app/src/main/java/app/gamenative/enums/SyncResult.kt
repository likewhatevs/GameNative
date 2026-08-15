package app.gamenative.enums

enum class SyncResult {
    Success,
    UpToDate,
    InProgress,
    PendingOperations,
    Conflict,
    UpdateFail,

    /** The local save set does not fit the app's Steam Cloud quota, so nothing was uploaded. */
    QuotaExceeded,

    DownloadFail,
    UnknownFail,
}
