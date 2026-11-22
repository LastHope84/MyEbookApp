package com.example.myebookapp

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Reader-only UI. TranslationRuntime must use installed models only, never download. */
internal class ReaderTranslationDialog(private val activity: FragmentActivity) : DefaultLifecycleObserver {
    private var dialog: AlertDialog? = null
    private var job: Job? = null

    fun show(text: String) {
        activity.lifecycle.addObserver(this)
        if (text.length > 12000) {
            message("Selection too long", "Please select a shorter passage (up to 12,000 characters).")
            return
        }
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, 0, padding, 0)
        }
        val source = EditText(activity).apply {
            hint = "Source language code (e.g. en)"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val target = EditText(activity).apply {
            hint = "Target language code (e.g. it)"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        layout.addView(source)
        layout.addView(target)
        val choices = AlertDialog.Builder(activity)
            .setTitle("Translate offline")
            .setMessage("Choose both language codes. Only installed language packs are used; nothing is downloaded.")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Translate", null)
            .create()
        dialog = choices
        choices.setOnDismissListener { if (dialog === choices) close() }
        choices.setOnShowListener {
            choices.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = source.text.toString().trim().lowercase(Locale.ROOT)
                val to = target.text.toString().trim().lowercase(Locale.ROOT)
                val code = Regex("[a-z]{2,3}")
                if (!code.matches(from)) {
                    source.error = "Enter a language code, for example en"
                    return@setOnClickListener
                }
                if (!code.matches(to) || from == to) {
                    target.error = "Enter a different language code, for example it"
                    return@setOnClickListener
                }
                choices.setOnDismissListener(null)
                choices.dismiss()
                if (!ThemePrefs.isOfflineTranslationEnabled(activity) || !TranslationRuntime.isAvailable()) {
                    message("Translation unavailable", "Enable offline translation in settings first.")
                } else {
                    translate(text, from, to)
                }
            }
        }
        choices.show()
    }

    private fun translate(text: String, source: String, target: String) {
        val progress = AlertDialog.Builder(activity)
            .setTitle("Translating offline")
            .setMessage("Using installed language packs…")
            .setNegativeButton("Cancel", null)
            .create()
        dialog = progress
        progress.setOnDismissListener { if (dialog === progress) close() }
        progress.show()
        job = activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    TranslationRuntime.translate(activity.applicationContext, source, target, text)
                }
                progress.setOnDismissListener(null)
                progress.dismiss()
                message("Translation", result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                progress.setOnDismissListener(null)
                progress.dismiss()
                val missing = generateSequence(error as Throwable?) { it.cause }
                    .take(20).any { it.javaClass.simpleName == "ModelNotInstalledException" }
                val detail = if (missing) {
                    "The required language pack is not installed. Install it in settings before trying again."
                } else {
                    "Translation failed. The language pair may be unsupported or its required pack may be missing. Check the installed packs and try again."
                }
                message("Unable to translate offline", detail)
            }
        }
    }

    private fun message(title: String, content: String) {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val body = TextView(activity).apply {
            text = content
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        val result = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .create()
        dialog = result
        result.setOnDismissListener { if (dialog === result) close() }
        result.show()
    }

    private fun close() {
        job?.cancel()
        job = null
        dialog = null
        activity.lifecycle.removeObserver(this)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        close()
    }
}
