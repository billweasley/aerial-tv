package app.orchard.tv.ui.home

import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import app.orchard.tv.R
import app.orchard.tv.apps.SourceLooks
import app.orchard.tv.system.TvSource

/** Uses the TV's installed keyboard; only an annotation is edited, never the detected device name. */
@Composable
internal fun SourceNoteDialog(source: TvSource, onDismiss: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(source.id) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(SourceLooks.MAX_NAME))
            hint = context.getString(R.string.source_note_hint)
            setText(SourceLooks.of(source.id)?.name.orEmpty())
            setSelection(text.length)
            setPadding(32, 20, 32, 20)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.source_note_title)
            .setMessage(listOfNotNull(source.deviceName, source.label).joinToString(" · "))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> SourceLooks.rename(source.id, input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { onDismiss() }
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
        onDispose { dialog.setOnDismissListener(null); dialog.dismiss() }
    }
}
