package com.lyrebird.rc

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.lyrebird.rc.mavlink.MavlinkSystemId

/**
 * A single, full-screen editor for the drone name and MAVLink vehicle ID.
 *
 * Both the main menu ([DJIMainActivity]) and Flight Deck ([FlightDeckActivity]) surface the same
 * fields; using one shared, scrollable subpage keeps the dialog identical everywhere and means it
 * scrolls on the RC's short landscape screen rather than clipping its content.
 */
object LyrebirdIdentityDialog {

    fun show(
        activity: Activity,
        title: String,
        subtitle: String,
        currentName: String,
        currentSysId: Int,
        onSave: (name: String?, sysId: Int) -> Unit
    ) {
        val dialog = Dialog(activity, R.style.LyrebirdSettingsDialog).apply {
            setContentView(R.layout.dialog_lyrebird_settings_subpage)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        val close = { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.text_subpage_title)?.text = title
        dialog.findViewById<TextView>(R.id.text_subpage_hint)?.text = subtitle
        dialog.findViewById<ImageButton>(R.id.button_settings_back)?.setOnClickListener { close() }
        dialog.findViewById<ImageButton>(R.id.button_subpage_close)?.setOnClickListener { close() }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                close()
                true
            } else {
                false
            }
        }
        dialog.window?.setWindowAnimations(0)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT
            )
        }
        val content = dialog.findViewById<LinearLayout>(R.id.settings_subpage_content) ?: return
        buildContent(activity, content, currentName, currentSysId, close, onSave)
    }

    private fun buildContent(
        activity: Activity,
        container: LinearLayout,
        currentName: String,
        currentSysId: Int,
        close: () -> Unit,
        onSave: (name: String?, sysId: Int) -> Unit
    ) {
        val density = activity.resources.displayMetrics.density
        fun dpToPx(dp: Int): Int = (dp * density).toInt()

        fun sectionLabel(label: String): TextView = TextView(activity).apply {
            text = label
            setTextColor(ContextCompat.getColor(activity, R.color.lyrebird_orange))
            textSize = 11f
            typeface = ResourcesCompat.getFont(activity, R.font.space_grotesk)
            letterSpacing = 0.16f
            setPadding(0, dpToPx(6), 0, dpToPx(8))
        }

        fun helpText(text: String): TextView = TextView(activity).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(activity, R.color.lyrebird_muted))
            textSize = 12f
            typeface = ResourcesCompat.getFont(activity, R.font.dm_sans)
            setPadding(0, dpToPx(4), 0, dpToPx(10))
        }

        fun saveButton(label: String, onClick: () -> Unit): Button = Button(activity).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = ResourcesCompat.getFont(activity, R.font.space_grotesk)
            setTextColor(ContextCompat.getColor(activity, R.color.lyrebird_background))
            background = ContextCompat.getDrawable(activity, R.drawable.lyrebird_settings_action)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(52)
            ).apply { bottomMargin = dpToPx(12) }
        }

        container.addView(sectionLabel("DRONE NAME"))
        val nameInput = EditText(activity).apply {
            setText(currentName)
            hint = "e.g. mini3, alpha, scout (blank = automatic)"
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(activity, R.color.lyrebird_text))
            setHintTextColor(ContextCompat.getColor(activity, R.color.lyrebird_muted))
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            setBackgroundResource(R.drawable.lyrebird_settings_row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(58)
            )
        }
        container.addView(nameInput)
        container.addView(helpText(activity.getString(R.string.drone_name_help)))

        container.addView(sectionLabel("MAVLINK VEHICLE ID"))
        val idInput = EditText(activity).apply {
            setText(if (MavlinkSystemId.isManual(currentSysId)) currentSysId.toString() else "")
            hint = "0 = automatic, 1-99 = manual"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setTextColor(ContextCompat.getColor(activity, R.color.lyrebird_text))
            setHintTextColor(ContextCompat.getColor(activity, R.color.lyrebird_muted))
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            setBackgroundResource(R.drawable.lyrebird_settings_row)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(58)
            )
        }
        container.addView(idInput)
        container.addView(helpText(activity.getString(R.string.vehicle_id_help)))

        container.addView(saveButton("Save") {
            val name = nameInput.text.toString().trim().ifEmpty { null }
            val idText = idInput.text.toString().trim()
            val id = if (idText.isBlank()) MavlinkSystemId.AUTO else idText.toIntOrNull()
            if (id == null || (id != MavlinkSystemId.AUTO && !MavlinkSystemId.isManual(id))) {
                Toast.makeText(activity, "Enter 0 for automatic, or 1-99 for a manual ID", Toast.LENGTH_SHORT).show()
            } else {
                onSave(name, id)
                close()
            }
        })
    }
}
