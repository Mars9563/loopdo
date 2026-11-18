package app.loopdo.client.popup

/** Text typed into a pop-up and not saved yet. It survives Back, a tap outside, a failed save, or the system closing the pop-up. */
interface PopupDrafts {
    fun load(key: String): String
    /** An empty [text] removes the draft. */
    fun save(key: String, text: String)
    fun keys(): Set<String>
}
