package com.lyrebird.rc.settings

import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.lyrebird.rc.R

internal class SettingsDialogViews(
    context: Context,
    private val showHome: () -> Unit,
) : ContextWrapper(context) {
    private fun showLyrebirdSettingsMenu() = showHome()

    fun dismiss() {
        lyrebirdSettingsDialog?.dismiss()
        lyrebirdSettingsDialog = null
    }

    internal var lyrebirdSettingsDialog: Dialog? = null

    internal data class SettingsActionRow(
        val title: String,
        val detail: String? = null,
        val enabled: Boolean = true,
    )

    internal fun dpToPx(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal fun actionRowAdapter(rows: List<SettingsActionRow>): ArrayAdapter<SettingsActionRow> {
        return object : ArrayAdapter<SettingsActionRow>(this, 0, rows) {
            override fun isEnabled(position: Int): Boolean = getItem(position)?.enabled == true

            override fun getView(
                position: Int,
                convertView: View?,
                parent: ViewGroup,
            ): View {
                val row = getItem(position) ?: SettingsActionRow("")
                val root =
                    (convertView as? LinearLayout) ?: LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dpToPx(18), dpToPx(12), dpToPx(14), dpToPx(12))
                        minimumHeight = dpToPx(68)
                    }
                root.removeAllViews()
                root.alpha = if (row.enabled) 1.0f else 0.45f
                root.background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFF7F9FC.toInt())
                        setStroke(dpToPx(1), 0xFFE1E7EF.toInt())
                    }

                val textColumn =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                textColumn.addView(
                    TextView(context).apply {
                        text = row.title
                        setTextColor(0xFF1F2937.toInt())
                        textSize = 15f
                        setTypeface(
                            ResourcesCompat.getFont(this@SettingsDialogViews, R.font.space_grotesk),
                            android.graphics.Typeface.BOLD,
                        )
                    },
                )
                row.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    textColumn.addView(
                        TextView(context).apply {
                            text = detail
                            setTextColor(0xFF5F6F82.toInt())
                            textSize = 13f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                    )
                }
                root.addView(textColumn)
                root.addView(
                    TextView(context).apply {
                        text = "›"
                        setTextColor(0xFF78C7FF.toInt())
                        textSize = 24f
                        setPadding(dpToPx(12), 0, 0, 0)
                        visibility = if (row.enabled) View.VISIBLE else View.INVISIBLE
                    },
                )
                return root
            }
        }
    }

    internal fun addCockpitSection(
        container: LinearLayout,
        label: String,
    ) {
        container.addView(
            TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_orange))
                textSize = 9f
                typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.space_grotesk)
                letterSpacing = 0.12f
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dpToPx(18),
                    )
            },
        )
    }

    internal fun addCockpitRow(
        container: LinearLayout,
        title: String,
        detail: String,
        onClick: (() -> Unit)? = null,
    ) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dpToPx(7), 0, dpToPx(7), 0)
                setBackgroundResource(R.drawable.lyrebird_settings_row)
                isClickable = onClick != null
                isFocusable = onClick != null
                onClick?.let { setOnClickListener { it() } }
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpToPx(28),
                        ).apply { bottomMargin = dpToPx(3) }
            }
        row.addView(
            TextView(this).apply {
                text = title
                setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_text))
                textSize = 10.5f
                typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.space_grotesk)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(this).apply {
                text = detail.ifBlank { "Unavailable" }
                setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_muted))
                textSize = 9.5f
                typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.dm_sans)
                gravity = android.view.Gravity.END
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        container.addView(row)
    }

    internal fun addCockpitStorageRow(
        container: LinearLayout,
        title: String,
        detail: String,
        iconRes: Int,
        onClick: () -> Unit,
    ) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), dpToPx(3), dpToPx(8), dpToPx(3))
                setBackgroundResource(R.drawable.lyrebird_settings_row)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpToPx(44),
                        ).apply { bottomMargin = dpToPx(4) }
            }
        row.addView(
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22))
                setImageResource(iconRes)
                imageTintList =
                    ColorStateList.valueOf(
                        ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_orange),
                    )
            },
        )
        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply { marginStart = dpToPx(8) }
                addView(
                    TextView(this@SettingsDialogViews).apply {
                        text = title
                        setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_text))
                        textSize = 12f
                        typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.space_grotesk)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                )
                addView(
                    TextView(this@SettingsDialogViews).apply {
                        text = detail.ifBlank { "Status unavailable" }
                        setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_muted))
                        textSize = 10.5f
                        typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.dm_sans)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                )
            },
        )
        container.addView(row)
    }

    internal fun formatCockpitLimit(value: Int): String = SettingsDisplay.limit(value)

    internal fun showBrandedSettingsSubpage(
        title: String,
        subtitle: String,
        onBack: () -> Unit = ::showLyrebirdSettingsMenu,
        configure: (LinearLayout, Dialog) -> Unit,
    ) {
        val previousDialog = lyrebirdSettingsDialog
        val dialog =
            Dialog(this, R.style.LyrebirdSettingsDialog).apply {
                setContentView(R.layout.dialog_lyrebird_settings_subpage)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
            }
        lyrebirdSettingsDialog = dialog
        dialog.findViewById<TextView>(R.id.text_subpage_title)?.text = title
        dialog.findViewById<TextView>(R.id.text_subpage_hint)?.text = subtitle
        dialog.findViewById<ImageButton>(R.id.button_settings_back)?.setOnClickListener {
            onBack()
        }
        dialog.findViewById<ImageButton>(R.id.button_subpage_close)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            if (lyrebirdSettingsDialog === dialog) lyrebirdSettingsDialog = null
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_DOWN
            ) {
                onBack()
                true
            } else {
                false
            }
        }
        dialog.window?.setWindowAnimations(0)
        previousDialog?.window?.setWindowAnimations(0)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
            )
        }
        previousDialog?.dismiss()
        val content = dialog.findViewById<LinearLayout>(R.id.settings_subpage_content)
        if (content != null) configure(content, dialog)
    }

    internal fun addBrandedSettingsSection(
        container: LinearLayout,
        label: String,
    ) {
        container.addView(
            TextView(this).apply {
                text = label
                setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_orange))
                textSize = 11f
                typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.space_grotesk)
                letterSpacing = 0.16f
                setPadding(0, dpToPx(6), 0, dpToPx(8))
            },
        )
    }

    internal fun addBrandedSettingsRow(
        container: LinearLayout,
        title: String,
        detail: String,
        icon: Int? = null,
        onClick: (() -> Unit)? = null,
    ) {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dpToPx(70)
                setPadding(dpToPx(16), dpToPx(10), dpToPx(14), dpToPx(10))
                setBackgroundResource(R.drawable.lyrebird_settings_row)
                isClickable = onClick != null
                isFocusable = onClick != null
                onClick?.let { setOnClickListener { it() } }
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = dpToPx(10)
                        }
            }
        icon?.let { iconRes ->
            row.addView(
                android.widget.ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(28), dpToPx(28))
                    setImageResource(iconRes)
                    imageTintList =
                        ColorStateList.valueOf(
                            ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_teal),
                        )
                },
            )
        }
        row.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (icon != null) marginStart = dpToPx(14)
                    }
                addView(
                    TextView(this@SettingsDialogViews).apply {
                        text = title
                        setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_text))
                        textSize = 15f
                        typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.space_grotesk)
                    },
                )
                if (detail.isNotBlank()) {
                    addView(
                        TextView(this@SettingsDialogViews).apply {
                            text = detail
                            setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_muted))
                            textSize = 12f
                            typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.dm_sans)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(0, dpToPx(2), 0, 0)
                        },
                    )
                }
            },
        )
        if (onClick != null) {
            row.addView(
                TextView(this).apply {
                    text = "›"
                    setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_teal))
                    textSize = 26f
                },
            )
        }
        container.addView(row)
    }

    internal fun addBrandedSettingsButton(
        container: LinearLayout,
        label: String,
        onClick: () -> Unit,
        destructive: Boolean = false,
    ) {
        container.addView(
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.space_grotesk)
                setTextColor(
                    ContextCompat.getColor(
                        this@SettingsDialogViews,
                        if (destructive) R.color.lyrebird_text else R.color.lyrebird_background,
                    ),
                )
                background =
                    ContextCompat.getDrawable(
                        this@SettingsDialogViews,
                        if (destructive) R.drawable.lyrebird_settings_row else R.drawable.lyrebird_settings_action,
                    )
                setOnClickListener { onClick() }
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dpToPx(52),
                        ).apply { bottomMargin = dpToPx(12) }
            },
        )
    }

    internal fun showBrandedChoicePage(
        title: String,
        subtitle: String,
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
        returnPage: () -> Unit,
        onBack: () -> Unit = returnPage,
    ) {
        showBrandedSettingsSubpage(title, subtitle, onBack) { container, dialog ->
            labels.forEachIndexed { index, label ->
                addBrandedSettingsRow(
                    container,
                    label,
                    if (index == selectedIndex) "Selected" else "",
                    onClick = {
                        onSelected(index)
                        returnPage()
                    },
                )
            }
        }
    }

    internal fun showBrandedEditPage(
        title: String,
        subtitle: String,
        currentValue: String,
        hint: String,
        saveLabel: String = "Save",
        onSave: (String) -> Unit,
        returnPage: () -> Unit,
        onBack: () -> Unit = returnPage,
    ) {
        showBrandedSettingsSubpage(title, subtitle, onBack) { container, dialog ->
            val input =
                EditText(this).apply {
                    setText(currentValue)
                    this.hint = hint
                    setSingleLine(true)
                    setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_text))
                    setHintTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_muted))
                    setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                    setBackgroundResource(R.drawable.lyrebird_settings_row)
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                dpToPx(58),
                            ).apply { bottomMargin = dpToPx(18) }
                }
            container.addView(input)
            addBrandedSettingsButton(container, saveLabel, {
                onSave(input.text.toString().trim())
                returnPage()
            })
        }
    }

    internal fun showBrandedIntegerEditPage(
        title: String,
        subtitle: String,
        currentValue: Int,
        hint: String,
        minimum: Int = 1,
        maximum: Int = Int.MAX_VALUE,
        resetLabel: String? = null,
        onReset: (() -> Unit)? = null,
        onSave: (Int) -> Unit,
        returnPage: () -> Unit = ::showLyrebirdSettingsMenu,
        onBack: () -> Unit = returnPage,
    ) {
        showBrandedSettingsSubpage(title, subtitle, onBack) { container, dialog ->
            val input =
                EditText(this).apply {
                    setText(if (currentValue >= 0) currentValue.toString() else "")
                    this.hint = hint
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER
                    setSingleLine(true)
                    setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_text))
                    setHintTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_muted))
                    setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                    setBackgroundResource(R.drawable.lyrebird_settings_row)
                    layoutParams =
                        LinearLayout
                            .LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                dpToPx(58),
                            ).apply { bottomMargin = dpToPx(18) }
                }
            container.addView(input)
            addBrandedSettingsButton(container, "Save", {
                val value =
                    input.text
                        .toString()
                        .trim()
                        .toIntOrNull()
                if (value == null || value < minimum || value > maximum) {
                    Toast
                        .makeText(
                            this,
                            "Enter a whole number from $minimum to $maximum",
                            Toast.LENGTH_SHORT,
                        ).show()
                } else {
                    onSave(value)
                    returnPage()
                }
            })
            if (resetLabel != null && onReset != null) {
                addBrandedSettingsButton(container, resetLabel, {
                    onReset()
                    returnPage()
                })
            }
        }
    }

    internal fun identityHelpText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(this@SettingsDialogViews, R.color.lyrebird_muted))
            textSize = 12f
            typeface = ResourcesCompat.getFont(this@SettingsDialogViews, R.font.dm_sans)
            setPadding(0, dpToPx(4), 0, dpToPx(10))
        }
}
